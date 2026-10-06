package group.gnometrading.oms.risk;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.collections.buffer.RingBuffer;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.RejectReason;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.EpochClock;

/**
 * Evaluates risk policies and kill switches on the order and market-time hot paths.
 *
 * <p>In production, a {@link RiskSyncAgent} running on a dedicated thread fetches policies, publishes a new
 * {@link RiskEngineSnapshot} whenever they change, and records each successful refresh. The hot path reads the
 * snapshot with a single volatile read — no sync work, no I/O. Everything else here belongs to the OMS thread,
 * except the refresh time and the latched-halt ring, which hands halts to the sync thread to record in the registry.
 *
 * <p>For tests and backtest, use {@link #RiskEngine(OrderPolicyGroup, MarketPolicyGroup)} to supply pre-built
 * global groups directly.
 */
public final class RiskEngine {

    // Halt causes, numbered as LogMessage.ORDER_REJECTED_HALTED names them.
    /** Risk data is stale: the registry hasn't been reached recently enough to trust that nothing was killed. */
    public static final int HALT_STALE = 0;
    /** A kill switch covers the order's strategy, session or listing. */
    public static final int HALT_KILLED = 1;
    /** The strategy latched itself: a loss limit breached, or the ledger stopped keeping up. */
    public static final int HALT_LATCHED = 2;

    // The sync thread drains this at least once a second, so it only fills if this many strategies breach in
    // between. A halt that doesn't fit stays latched here, it just isn't recorded for an operator to resume.
    private static final int LATCHED_HALT_CAPACITY = 1024;
    private static final int EXPECTED_LATCHES = 64;
    // Staleness is judged here on the OMS thread, not by the sync thread, which a hung registry call would stop.
    // A spinning OMS loop still checks far more often than once a millisecond while reading the clock rarely.
    private static final int STALE_CHECK_PASSES = 1024;
    private static final long NEVER = Long.MIN_VALUE;

    private final AtomicReference<RiskEngineSnapshot> snapshot;
    private RiskEngineSnapshot applied;
    private final IntHashSet latchedStrategies = new IntHashSet(EXPECTED_LATCHES);
    private final RingBuffer<LatchedHalt> latchedHalts =
            new OneToOneRingBuffer<>(LatchedHalt[]::new, LatchedHalt::new, LATCHED_HALT_CAPACITY);

    private final EpochClock clock;
    private final long staleAfterMs;
    private final int staleCheckPasses;
    private volatile long lastRefreshMs = NEVER;
    private volatile long appliedSequence;
    private boolean stale;
    private int violatedPolicyId;
    private int breachedPolicyId;
    private int haltCause;
    private int passesUntilStaleCheck;

    private final IntConsumer releaseLatch = this::releaseLatch;
    private final IntConsumer reportNewStrategyKill = this::reportNewStrategyKill;
    private final IntConsumer reportNewListingKill = this::reportNewListingKill;
    private final IntConsumer reportStrategyKill = this::reportStrategyKill;
    private final IntConsumer reportListingKill = this::reportListingKill;
    private final LongConsumer reportNewStrategyListingKill = this::reportNewStrategyListingKill;
    private final LongConsumer reportStrategyListingKill = this::reportStrategyListingKill;
    private RiskEngineSnapshot reportingAgainst;
    private KillHandler reportingTo;

    /**
     * Test/backtest constructor. Uses the provided groups as the global order and market groups.
     * No dynamic sync is expected.
     */
    @VisibleForTesting
    public RiskEngine(final OrderPolicyGroup globalOrderGroup, final MarketPolicyGroup globalMarketGroup) {
        this(new RiskEngineSnapshot(), null, 0, 0);
        final RiskEngineSnapshot initial = snapshot.get();
        initial.globalOrderGroup.count = globalOrderGroup.count;
        System.arraycopy(globalOrderGroup.policies, 0, initial.globalOrderGroup.policies, 0, globalOrderGroup.count);
        initial.globalMarketGroup.count = globalMarketGroup.count;
        System.arraycopy(globalMarketGroup.policies, 0, initial.globalMarketGroup.policies, 0, globalMarketGroup.count);
    }

    /** An engine with no policies that allows everything; for tests and backtests without a sync agent. */
    public RiskEngine() {
        this(new RiskEngineSnapshot(), null, 0, 0);
    }

    private RiskEngine(
            final RiskEngineSnapshot initial,
            final EpochClock clock,
            final long staleAfterMs,
            final int staleCheckPasses) {
        this.snapshot = new AtomicReference<>(initial);
        this.applied = initial;
        this.clock = clock;
        this.staleAfterMs = staleAfterMs;
        this.staleCheckPasses = staleCheckPasses;
        // Never refreshed means the policies, kill switches included, are unknown.
        this.stale = clock != null;
    }

    /**
     * Production engine, kept current by a {@link RiskSyncAgent}. New orders are blocked until the first refresh
     * succeeds, and again whenever none has for {@code staleAfter}: trading on policies nobody can see would ignore
     * a kill switch turned on meanwhile. Resting orders are left alone, since a stale view is not a kill.
     */
    public static RiskEngine syncedFromRegistry(final EpochClock clock, final Duration staleAfter) {
        return syncedFromRegistry(clock, staleAfter, STALE_CHECK_PASSES);
    }

    static RiskEngine syncedFromRegistry(final EpochClock clock, final Duration staleAfter, final int checkEvery) {
        return new RiskEngine(new RiskEngineSnapshot(), clock, staleAfter.toMillis(), checkEvery);
    }

    /**
     * Creates a RiskEngine pre-loaded with the given order-time policies in the global order
     * group. Intended for backtest and test use where no {@link RiskSyncAgent} is running.
     */
    public static RiskEngine withOrderPolicies(final OrderRiskPolicy... policies) {
        final OrderPolicyGroup orderGroup = new OrderPolicyGroup(Math.max(policies.length, 1));
        for (final OrderRiskPolicy p : policies) {
            orderGroup.add(p, 0);
        }
        return new RiskEngine(orderGroup, new MarketPolicyGroup(1));
    }

    /**
     * Creates a RiskEngine pre-loaded with the given order-time and market-time policies in the
     * global groups. Intended for backtest and test use where no {@link RiskSyncAgent} is running.
     */
    public static RiskEngine withPolicies(
            final OrderRiskPolicy[] orderPolicies, final MarketRiskPolicy[] marketPolicies) {
        final OrderPolicyGroup orderGroup = new OrderPolicyGroup(Math.max(orderPolicies.length, 1));
        for (final OrderRiskPolicy p : orderPolicies) {
            orderGroup.add(p, 0);
        }
        final MarketPolicyGroup marketGroup = new MarketPolicyGroup(Math.max(marketPolicies.length, 1));
        for (final MarketRiskPolicy p : marketPolicies) {
            marketGroup.add(p, 0);
        }
        return new RiskEngine(orderGroup, marketGroup);
    }

    /** A policy and the target it applies to, as the registry describes one: 0 means every strategy or listing. */
    public record ScopedPolicy(int strategyId, int listingId, Configurable policy) {
        public ScopedPolicy {
            if (strategyId < 0 || listingId < 0) {
                throw new IllegalArgumentException(
                        "strategyId and listingId must be 0 (all) or positive, got " + strategyId + "/" + listingId);
            }
        }
    }

    /**
     * An engine whose policies are fixed at construction, each placed in the group its target selects exactly as
     * {@link RiskSyncAgent} places registry policies. For backtests, which run a strategy under the limits it would
     * have live without a sync agent.
     */
    public static RiskEngine withScopedPolicies(final List<ScopedPolicy> policies) {
        final RiskEngineSnapshot initial = new RiskEngineSnapshot();
        for (final ScopedPolicy scoped : policies) {
            initial.addPolicy(scoped.strategyId(), scoped.listingId(), scoped.policy(), 0);
        }
        return new RiskEngine(initial, null, 0, 0);
    }

    void publishSnapshot(final RiskEngineSnapshot newSnapshot) {
        snapshot.set(newSnapshot);
    }

    @VisibleForTesting
    RiskEngineSnapshot publishedSnapshot() {
        return snapshot.get();
    }

    /** Records a successful policy refresh. Sync thread only. */
    void recordRefresh(final long nowMs) {
        lastRefreshMs = nowMs;
    }

    /** The sequence of the latest snapshot the OMS thread has applied. Sync thread reads it. */
    long appliedSequence() {
        return appliedSequence;
    }

    /**
     * Whether this strategy may do nothing at all on this listing: it is killed or latched, so its orders have been
     * cancelled. Unlike {@link #isBlocked}, stale risk is excluded — the strategy can still pull its own quotes.
     */
    public boolean isHalted(final int strategyId, final int listingId) {
        return snapshot.get().blocks(strategyId, listingId) || latchedStrategies.contains(strategyId);
    }

    /** Whether new orders for this strategy on this listing are blocked by a kill, a latch or stale risk. */
    public boolean isBlocked(final int strategyId, final int listingId) {
        return blocked(snapshot.get(), strategyId, listingId);
    }

    private boolean blocked(final RiskEngineSnapshot current, final int strategyId, final int listingId) {
        return stale || current.blocks(strategyId, listingId) || latchedStrategies.contains(strategyId);
    }

    /** Whether a breach on this strategy is already held, by its own kill or a latch, so need not be re-checked. */
    public boolean isStrategyHalted(final int strategyId) {
        return snapshot.get().killedStrategies.contains(strategyId) || latchedStrategies.contains(strategyId);
    }

    /** Whether any market-risk policy is configured, whatever it applies to. */
    public boolean hasMarketPolicies() {
        final RiskEngineSnapshot s = snapshot.get();
        return s.globalMarketGroup.count > 0
                || !s.strategyMarketGroups.isEmpty()
                || !s.listingMarketGroups.isEmpty()
                || !s.strategyListingMarketGroups.isEmpty();
    }

    @VisibleForTesting
    public boolean isLatched(final int strategyId) {
        return latchedStrategies.contains(strategyId);
    }

    /**
     * Halts a strategy after a market-risk breach until an operator resumes it. The halt is handed to the sync
     * thread to record as a strategy kill switch in the registry; once a snapshot confirms it, the registry holds
     * the halt and this latch is released.
     *
     * @return whether the strategy was newly halted, so its orders still need cancelling
     */
    public boolean latch(final int strategyId) {
        // Only a kill on this strategy already holds the halt in the registry; a global block lifts by itself and
        // must not release a breach.
        if (snapshot.get().killedStrategies.contains(strategyId) || !latchedStrategies.add(strategyId)) {
            return false;
        }
        final int index = latchedHalts.tryClaim();
        if (index >= 0) {
            latchedHalts.indexAt(index).strategyId = strategyId;
            latchedHalts.commit(index);
        }
        return true;
    }

    /** Hands each halt latched since the last call to {@code consumer}. Sync thread only. */
    void drainLatchedHalts(final MessageConsumer<LatchedHalt> consumer) {
        latchedHalts.read(consumer, LATCHED_HALT_CAPACITY);
    }

    /** A strategy halted after a market-risk breach, awaiting a strategy kill switch in the registry. */
    static final class LatchedHalt {
        int strategyId;
    }

    /**
     * Brings the OMS thread's view up to date: refreshes the stale flag every few passes and applies a newly
     * published snapshot, reporting each scope that has just been killed so its orders can be cancelled. Scopes
     * that stay killed, or come back, need nothing here: the order checks read the snapshot. OMS thread only.
     */
    public void applyChanges(final KillHandler handler) {
        if (clock != null && --passesUntilStaleCheck <= 0) {
            passesUntilStaleCheck = staleCheckPasses;
            final long refreshedAt = lastRefreshMs;
            stale = refreshedAt == NEVER || clock.time() - refreshedAt > staleAfterMs;
        }
        final RiskEngineSnapshot current = snapshot.get();
        if (current == applied) {
            return;
        }
        final RiskEngineSnapshot previous = applied;
        applied = current;
        appliedSequence = current.sequence;
        if (!latchedStrategies.isEmpty()) {
            current.killedStrategies.forEachInt(releaseLatch);
            current.confirmedHalts.forEachInt(releaseLatch);
        }

        if (current.globalKill) {
            if (!previous.globalKill) {
                handler.onEverythingKilled();
            }
            return;
        }
        if (!previous.globalKill) {
            reportingAgainst = previous;
            reportingTo = handler;
            current.killedStrategies.forEachInt(reportNewStrategyKill);
            current.killedListings.forEachInt(reportNewListingKill);
            current.killedStrategyListings.forEachLong(reportNewStrategyListingKill);
        }
    }

    /** Whether anything is killed or latched, so its orders may still need cancelling. OMS thread only. */
    public boolean hasKills() {
        return applied.hasKills() || !latchedStrategies.isEmpty();
    }

    /**
     * Reports every scope that is currently killed or latched, so orders whose cancels the venue refused can be
     * cancelled again. OMS thread only.
     */
    public void forEachKilledScope(final KillHandler handler) {
        if (applied.globalKill) {
            handler.onEverythingKilled();
            return;
        }
        reportingTo = handler;
        applied.killedStrategies.forEachInt(reportStrategyKill);
        latchedStrategies.forEachInt(reportStrategyKill);
        applied.killedListings.forEachInt(reportListingKill);
        applied.killedStrategyListings.forEachLong(reportStrategyListingKill);
    }

    private void releaseLatch(final int strategyId) {
        latchedStrategies.remove(strategyId);
    }

    private void reportNewStrategyKill(final int strategyId) {
        if (!reportingAgainst.killedStrategies.contains(strategyId)) {
            reportingTo.onStrategyKilled(strategyId);
        }
    }

    private void reportNewListingKill(final int listingId) {
        if (!reportingAgainst.killedListings.contains(listingId)) {
            reportingTo.onListingKilled(listingId);
        }
    }

    private void reportNewStrategyListingKill(final long key) {
        if (!reportingAgainst.killedStrategyListings.contains(key)) {
            reportStrategyListingKill(key);
        }
    }

    private void reportStrategyListingKill(final long key) {
        reportingTo.onStrategyListingKilled(RiskEngineSnapshot.pairStrategy(key), RiskEngineSnapshot.pairListing(key));
    }

    private void reportStrategyKill(final int strategyId) {
        reportingTo.onStrategyKilled(strategyId);
    }

    private void reportListingKill(final int listingId) {
        reportingTo.onListingKilled(listingId);
    }

    /** Receives killed scopes. */
    public interface KillHandler {
        void onEverythingKilled();

        void onStrategyKilled(int strategyId);

        void onListingKilled(int listingId);

        void onStrategyListingKilled(int strategyId, int listingId);
    }

    /**
     * Why an order may not go out, or null if it may. {@link RejectReason#HALTED} means nothing may trade in this
     * scope, and {@link #haltCause()} says why; {@link RejectReason#RISK_LIMIT_EXCEEDED} means this order broke a
     * limit, and {@link #violatedPolicyId()} names it. OMS thread only.
     */
    public RejectReason check(
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int strategyId,
            final int listingId) {
        final RiskEngineSnapshot s = snapshot.get();
        if (stale) {
            haltCause = HALT_STALE;
            return RejectReason.HALTED;
        }
        if (s.blocks(strategyId, listingId)) {
            haltCause = HALT_KILLED;
            return RejectReason.HALTED;
        }
        if (latchedStrategies.contains(strategyId)) {
            haltCause = HALT_LATCHED;
            return RejectReason.HALTED;
        }
        final boolean passes = checkOrderGroup(s.globalOrderGroup, order, positions, orders, strategyId, listingId)
                && checkOrderGroup(s.getStrategyOrderGroup(strategyId), order, positions, orders, strategyId, listingId)
                && checkOrderGroup(
                        s.getStrategyListingOrderGroup(strategyId, listingId),
                        order,
                        positions,
                        orders,
                        strategyId,
                        listingId)
                && checkOrderGroup(s.getListingOrderGroup(listingId), order, positions, orders, strategyId, listingId);
        return passes ? null : RejectReason.RISK_LIMIT_EXCEEDED;
    }

    /** The registry id of the policy behind the last {@link RejectReason#RISK_LIMIT_EXCEEDED}; 0 if it has none. */
    public int violatedPolicyId() {
        return violatedPolicyId;
    }

    /** The registry id of the policy behind the last breach {@link #checkMarketPolicies} found; 0 if it has none. */
    public int breachedPolicyId() {
        return breachedPolicyId;
    }

    /** Why the last {@link RejectReason#HALTED} was: {@link #HALT_STALE}, {@link #HALT_KILLED} or {@link #HALT_LATCHED}. */
    public int haltCause() {
        return haltCause;
    }

    /**
     * Checks market-risk policies after a fill or a mark move on {@code listingId}. Each policy knows whether it
     * judges that listing or the strategy's total. Returns true if any policy is violated.
     */
    public boolean checkMarketPolicies(
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final RiskEngineSnapshot s = snapshot.get();
        return isMarketGroupViolated(s.globalMarketGroup, strategyId, listingId, positions, orders)
                || isMarketGroupViolated(s.getStrategyMarketGroup(strategyId), strategyId, listingId, positions, orders)
                || isMarketGroupViolated(
                        s.getStrategyListingMarketGroup(strategyId, listingId),
                        strategyId,
                        listingId,
                        positions,
                        orders)
                || isMarketGroupViolated(s.getListingMarketGroup(listingId), strategyId, listingId, positions, orders);
    }

    private boolean checkOrderGroup(
            final OrderPolicyGroup group,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int strategyId,
            final int listingId) {
        if (group == null) {
            return true;
        }
        for (int i = 0; i < group.count; i++) {
            if (group.policies[i].isViolated(strategyId, listingId, order, positions, orders)) {
                violatedPolicyId = group.policyIds[i];
                return false;
            }
        }
        return true;
    }

    private boolean isMarketGroupViolated(
            final MarketPolicyGroup group,
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        if (group == null) {
            return false;
        }
        for (int i = 0; i < group.count; i++) {
            if (group.policies[i].isViolated(strategyId, listingId, positions, orders)) {
                breachedPolicyId = group.policyIds[i];
                return true;
            }
        }
        return false;
    }
}
