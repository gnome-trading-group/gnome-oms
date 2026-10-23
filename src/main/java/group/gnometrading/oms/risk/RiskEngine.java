package group.gnometrading.oms.risk;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.collections.buffer.RingBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.LongHashSet;
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
    /** A kill switch covers the order's strategy, session, exchange or listing. */
    public static final int HALT_KILLED = 1;
    /** The strategy latched itself in this scope: a market limit breached, or the ledger stopped keeping up. */
    public static final int HALT_LATCHED = 2;
    /** A live session lacks a policy every listing must have, so nothing may trade until it is added. */
    public static final int HALT_UNGUARDED = 3;

    // Why a strategy latched; the first two are numbered as LogMessage.STRATEGY_LATCHED names them.
    /** A market-risk policy, such as a loss limit, breached. */
    public static final int CAUSE_MARKET_LIMIT = 0;
    /** The venue rejected too many orders in a row. */
    public static final int CAUSE_REJECT_STREAK = 1;
    /** The ledger can't record what happens next. */
    public static final int CAUSE_LEDGER = 2;

    // The sync thread drains this at least once a second, so it only fills if this many strategies breach in
    // between. A halt that doesn't fit stays latched here, it just isn't recorded for an operator to resume.
    private static final int LATCHED_HALT_CAPACITY = 1024;
    private static final int EXPECTED_LATCHES = 64;
    // Staleness is judged here on the OMS thread, not by the sync thread, which a hung registry call would stop.
    // A spinning OMS loop still checks far more often than once a millisecond while reading the clock rarely.
    private static final int STALE_CHECK_PASSES = 1024;
    private static final long NEVER = Long.MIN_VALUE;
    private static final long FIRST_REFRESH_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final AtomicReference<RiskEngineSnapshot> snapshot;
    private RiskEngineSnapshot applied;
    private final ListingDirectory directory;
    private final RejectStreaks rejectStreaks;
    private final IntHashSet latchedStrategies = new IntHashSet(EXPECTED_LATCHES);
    private final LongHashSet latchedStrategyExchanges = new LongHashSet(EXPECTED_LATCHES);
    private final LongHashSet latchedStrategyListings = new LongHashSet(EXPECTED_LATCHES);
    private final RingBuffer<LatchedHalt> latchedHalts =
            new OneToOneRingBuffer<>(LatchedHalt[]::new, LatchedHalt::new, LATCHED_HALT_CAPACITY);

    private final EpochClock clock;
    private final long staleAfterMs;
    private final int staleCheckPasses;
    private volatile long lastRefreshMs = NEVER;
    private volatile long appliedSequence;
    // Latches that didn't fit the ring, so were never recorded in the registry; written by the OMS thread only.
    private final AtomicLong droppedLatches = new AtomicLong();
    private boolean stale;
    private int violatedPolicyId;
    private int breachedPolicyId;
    private int breachedCause;
    private int breachedExchangeId;
    private int breachedListingId;
    private int latchExchangeId;
    private int latchListingId;
    private int haltCause;
    private int passesUntilStaleCheck;

    private final IntConsumer releaseStrategyLatch = latchedStrategies::remove;
    private final LongConsumer releaseStrategyExchangeLatch = latchedStrategyExchanges::remove;
    private final LongConsumer releaseStrategyListingLatch = latchedStrategyListings::remove;
    private final IntConsumer reportNewStrategyKill = this::reportNewStrategyKill;
    private final IntConsumer reportNewExchangeKill = this::reportNewExchangeKill;
    private final IntConsumer reportNewListingKill = this::reportNewListingKill;
    private final LongConsumer reportNewStrategyExchangeKill = this::reportNewStrategyExchangeKill;
    private final LongConsumer reportNewStrategyListingKill = this::reportNewStrategyListingKill;
    private final IntConsumer reportStrategyKill = this::reportStrategyKill;
    private final IntConsumer reportExchangeKill = this::reportExchangeKill;
    private final IntConsumer reportListingKill = this::reportListingKill;
    private final LongConsumer reportStrategyExchangeKill = this::reportStrategyExchangeKill;
    private final LongConsumer reportStrategyListingKill = this::reportStrategyListingKill;
    private RiskEngineSnapshot reportingAgainst;
    private KillHandler reportingTo;

    /**
     * Test/backtest constructor. Uses the provided groups as the global order and market groups.
     * No dynamic sync is expected.
     */
    @VisibleForTesting
    public RiskEngine(final OrderPolicyGroup globalOrderGroup, final MarketPolicyGroup globalMarketGroup) {
        this(new RiskEngineSnapshot(), null, 0, 0, ListingDirectory.EMPTY, new RejectStreaks());
        final RiskEngineSnapshot initial = snapshot.get();
        final OrderPolicyGroup orders = initial.orderGroups.global;
        orders.count = globalOrderGroup.count;
        System.arraycopy(globalOrderGroup.policies, 0, orders.policies, 0, globalOrderGroup.count);
        final MarketPolicyGroup markets = initial.marketGroups.global;
        markets.count = globalMarketGroup.count;
        System.arraycopy(globalMarketGroup.policies, 0, markets.policies, 0, globalMarketGroup.count);
    }

    /** An engine with no policies that allows everything; for tests and backtests without a sync agent. */
    public RiskEngine() {
        this(new RiskEngineSnapshot(), null, 0, 0, ListingDirectory.EMPTY, new RejectStreaks());
    }

    private RiskEngine(
            final RiskEngineSnapshot initial,
            final EpochClock clock,
            final long staleAfterMs,
            final int staleCheckPasses,
            final ListingDirectory directory,
            final RejectStreaks rejectStreaks) {
        this.snapshot = new AtomicReference<>(initial);
        this.applied = initial;
        this.clock = clock;
        this.staleAfterMs = staleAfterMs;
        this.staleCheckPasses = staleCheckPasses;
        this.directory = directory;
        this.rejectStreaks = rejectStreaks;
        // Never refreshed means the policies, kill switches included, are unknown.
        this.stale = clock != null;
    }

    /**
     * Production engine, kept current by a {@link RiskSyncAgent}. New orders are blocked until the first refresh
     * succeeds, and again whenever none has for {@code staleAfter}: trading on policies nobody can see would ignore
     * a kill switch turned on meanwhile. Resting orders are left alone, since a stale view is not a kill.
     */
    public static RiskEngine syncedFromRegistry(
            final EpochClock clock, final Duration staleAfter, final ListingDirectory directory) {
        return syncedFromRegistry(clock, staleAfter, directory, STALE_CHECK_PASSES);
    }

    static RiskEngine syncedFromRegistry(
            final EpochClock clock, final Duration staleAfter, final ListingDirectory directory, final int checkEvery) {
        return new RiskEngine(
                new RiskEngineSnapshot(), clock, staleAfter.toMillis(), checkEvery, directory, new RejectStreaks());
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

    /**
     * A policy and the target it applies to, as the registry describes one: 0 means every strategy, listing or
     * exchange.
     */
    public record ScopedPolicy(int strategyId, int listingId, int exchangeId, Configurable policy) {
        public ScopedPolicy {
            if (strategyId < 0 || listingId < 0 || exchangeId < 0) {
                throw new IllegalArgumentException(
                        "strategyId, listingId and exchangeId must be 0 (all) or positive, got " + strategyId + "/"
                                + listingId + "/" + exchangeId);
            }
        }
    }

    /**
     * An engine whose policies are fixed at construction, each placed in the group its target selects exactly as
     * {@link RiskSyncAgent} places registry policies. For backtests, which run a strategy under the limits it would
     * have live without a sync agent. The policies must have been built by a {@link PolicyFactory} given the same
     * {@code directory} and {@code rejectStreaks}.
     */
    public static RiskEngine withScopedPolicies(
            final List<ScopedPolicy> policies, final ListingDirectory directory, final RejectStreaks rejectStreaks) {
        final RiskEngineSnapshot initial = new RiskEngineSnapshot();
        for (final ScopedPolicy scoped : policies) {
            initial.addPolicy(scoped.strategyId(), scoped.listingId(), scoped.exchangeId(), scoped.policy(), 0);
        }
        return new RiskEngine(initial, null, 0, 0, directory, rejectStreaks);
    }

    /** The session's listings, as the policies this engine runs see them. */
    public ListingDirectory directory() {
        return directory;
    }

    /** The venue reject streaks {@link MaxConsecutiveRejectsPolicy} judges; the OMS records them. */
    public RejectStreaks rejectStreaks() {
        return rejectStreaks;
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
    public boolean isHalted(final int strategyId, final int listingId, final int exchangeId) {
        return snapshot.get().blocks(strategyId, listingId, exchangeId) || isLatched(strategyId, exchangeId, listingId);
    }

    /** Whether new orders for this strategy on this listing are blocked by a kill, a latch or stale risk. */
    public boolean isBlocked(final int strategyId, final int listingId, final int exchangeId) {
        return stale || isHalted(strategyId, listingId, exchangeId);
    }

    private boolean isLatched(final int strategyId, final int exchangeId, final int listingId) {
        return latchedStrategies.contains(strategyId)
                || (!latchedStrategyExchanges.isEmpty()
                        && latchedStrategyExchanges.contains(RiskEngineSnapshot.pairKey(strategyId, exchangeId)))
                || (!latchedStrategyListings.isEmpty()
                        && latchedStrategyListings.contains(RiskEngineSnapshot.pairKey(strategyId, listingId)));
    }

    /** Whether a breach on this strategy is already held, by its own kill or a latch, so need not be re-checked. */
    public boolean isStrategyHalted(final int strategyId) {
        return snapshot.get().killedStrategies.contains(strategyId) || latchedStrategies.contains(strategyId);
    }

    /** Whether any market-risk policy is configured, whatever it applies to. */
    public boolean hasMarketPolicies() {
        return snapshot.get().hasMarketPolicies();
    }

    /** Whether the strategy is latched in exactly this scope; 0 is every exchange or listing. */
    @VisibleForTesting
    public boolean isLatchedAt(final int strategyId, final int exchangeId, final int listingId) {
        if (listingId != 0) {
            return latchedStrategyListings.contains(RiskEngineSnapshot.pairKey(strategyId, listingId));
        }
        if (exchangeId != 0) {
            return latchedStrategyExchanges.contains(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
        }
        return latchedStrategies.contains(strategyId);
    }

    /**
     * Halts a strategy in a scope until an operator resumes it: everywhere, on one exchange, or on one listing (0
     * leaves that id out; at most one of the two is set). The halt is handed to the sync thread to record as a kill
     * switch on the same scope in the registry; once a snapshot confirms it, the registry holds the halt and this
     * latch is released. Ends the scope's reject streaks, so a resumed strategy starts from none.
     *
     * @return whether the scope was newly halted, so its orders still need cancelling
     */
    public boolean latch(
            final int strategyId, final int exchangeId, final int listingId, final int cause, final int policyId) {
        return latch(strategyId, exchangeId, listingId, cause, policyId, 0);
    }

    private boolean latch(
            final int strategyId,
            final int exchangeId,
            final int listingId,
            final int cause,
            final int policyId,
            final long rejects) {
        // Only a kill on this very scope already holds the halt in the registry; a wider block may lift by itself
        // and must not release a breach.
        if (snapshot.get().killsExactly(strategyId, exchangeId, listingId)) {
            return false;
        }
        final boolean added;
        if (listingId != 0) {
            added = latchedStrategyListings.add(RiskEngineSnapshot.pairKey(strategyId, listingId));
        } else if (exchangeId != 0) {
            added = latchedStrategyExchanges.add(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
        } else {
            added = latchedStrategies.add(strategyId);
        }
        if (!added) {
            return false;
        }
        rejectStreaks.clear(strategyId, exchangeId, listingId);
        final int index = latchedHalts.tryClaim();
        if (index < 0) {
            droppedLatches.incrementAndGet();
        } else {
            final LatchedHalt halt = latchedHalts.indexAt(index);
            halt.strategyId = strategyId;
            halt.exchangeId = exchangeId;
            halt.listingId = listingId;
            halt.cause = cause;
            halt.policyId = policyId;
            halt.rejects = rejects;
            latchedHalts.commit(index);
        }
        return true;
    }

    /**
     * Latches the strategy for the breach {@link #checkMarketPolicies} just found, in the breached policy's scope.
     * {@code fundsExchangeId} is the exchange of a reject for insufficient funds that tripped a reject streak, or 0:
     * an empty account on one venue says nothing about the strategy's others, nor is it particular to one listing,
     * so such a streak latches the strategy on that exchange whatever its policy's scope. {@link #latchExchangeId}
     * and {@link #latchListingId} then name the scope latched.
     *
     * @return whether the scope was newly halted, so its orders still need cancelling
     */
    public boolean latchBreach(final int strategyId, final int fundsExchangeId) {
        final long rejects = breachedCause == CAUSE_REJECT_STREAK ? breachedStreak(strategyId) : 0;
        if (fundsExchangeId != 0 && breachedCause == CAUSE_REJECT_STREAK) {
            latchExchangeId = fundsExchangeId;
            latchListingId = 0;
        } else {
            latchExchangeId = breachedExchangeId;
            latchListingId = breachedListingId;
        }
        return latch(strategyId, latchExchangeId, latchListingId, breachedCause, breachedPolicyId, rejects);
    }

    private long breachedStreak(final int strategyId) {
        if (breachedListingId != 0) {
            return rejectStreaks.ofListing(strategyId, breachedListingId);
        }
        if (breachedExchangeId != 0) {
            return rejectStreaks.ofExchange(strategyId, breachedExchangeId);
        }
        return rejectStreaks.ofStrategy(strategyId);
    }

    /** The exchange of the scope {@link #latchBreach} last latched, or 0 if it covered every exchange. */
    public int latchExchangeId() {
        return latchExchangeId;
    }

    /** The listing of the scope {@link #latchBreach} last latched, or 0 if it covered every listing. */
    public int latchListingId() {
        return latchListingId;
    }

    /** Why the last breach {@link #checkMarketPolicies} found happened: {@link #CAUSE_MARKET_LIMIT} or a streak. */
    public int breachedCause() {
        return breachedCause;
    }

    /** Hands each halt latched since the last call to {@code consumer}. Sync thread only. */
    void drainLatchedHalts(final MessageConsumer<LatchedHalt> consumer) {
        latchedHalts.read(consumer, LATCHED_HALT_CAPACITY);
    }

    /** A strategy halted in a scope, awaiting a kill switch on that scope in the registry. */
    static final class LatchedHalt {
        int strategyId;
        int exchangeId;
        int listingId;
        int cause;
        int policyId;
        /** For a reject streak, how long it was. */
        long rejects;
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
        releaseHeldLatches(current);

        if (current.stopsEverything()) {
            if (!previous.stopsEverything()) {
                handler.onEverythingKilled();
            }
            return;
        }
        if (!previous.stopsEverything()) {
            reportingAgainst = previous;
            reportingTo = handler;
            current.killedStrategies.forEachInt(reportNewStrategyKill);
            current.killedExchanges.forEachInt(reportNewExchangeKill);
            current.killedListings.forEachInt(reportNewListingKill);
            current.killedStrategyExchanges.forEachLong(reportNewStrategyExchangeKill);
            current.killedStrategyListings.forEachLong(reportNewStrategyListingKill);
        }
    }

    /** A latch is over once the registry holds its halt: a kill on its scope, or a confirmation of its record. */
    private void releaseHeldLatches(final RiskEngineSnapshot current) {
        if (!latchedStrategies.isEmpty()) {
            current.killedStrategies.forEachInt(releaseStrategyLatch);
            current.confirmedStrategyHalts.forEachInt(releaseStrategyLatch);
        }
        if (!latchedStrategyExchanges.isEmpty()) {
            current.killedStrategyExchanges.forEachLong(releaseStrategyExchangeLatch);
            current.confirmedStrategyExchangeHalts.forEachLong(releaseStrategyExchangeLatch);
        }
        if (!latchedStrategyListings.isEmpty()) {
            current.killedStrategyListings.forEachLong(releaseStrategyListingLatch);
            current.confirmedStrategyListingHalts.forEachLong(releaseStrategyListingLatch);
        }
    }

    /**
     * Whether risk is stale as of {@code nowMs}: what the OMS will find on its next check. Any thread, for reporting;
     * the OMS's own decisions read its cached flag.
     */
    public boolean isStale(final long nowMs) {
        final long refreshedAt = lastRefreshMs;
        return clock != null && (refreshedAt == NEVER || nowMs - refreshedAt > staleAfterMs);
    }

    /**
     * Blocks until the first refresh succeeds, for at most the stale threshold, and says whether it did. A session
     * waits here before starting its strategy, whose first orders would otherwise be refused as stale while the first
     * registry call is still in flight. Waiting longer is pointless: past the threshold the engine would be stale
     * anyway. An engine without a registry is never stale, so it never waits.
     */
    public boolean awaitFirstRefresh() {
        if (clock == null) {
            return true;
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(staleAfterMs);
        while (lastRefreshMs == NEVER) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            LockSupport.parkNanos(FIRST_REFRESH_POLL_NANOS);
        }
        return true;
    }

    /** Whether the latest policies leave a required one missing, so every order is refused. Any thread. */
    public boolean isUnguarded() {
        return snapshot.get().unguarded;
    }

    /** How many policies in the latest snapshot couldn't be built and killed their targets instead. Any thread. */
    public int unbuildablePolicies() {
        return snapshot.get().unbuildablePolicies;
    }

    /** Latches never recorded in the registry because the ring was full. Any thread. */
    public long droppedLatches() {
        return droppedLatches.get();
    }

    /** Whether anything is killed or latched, so its orders may still need cancelling. OMS thread only. */
    public boolean hasKills() {
        return applied.hasKills()
                || !latchedStrategies.isEmpty()
                || !latchedStrategyExchanges.isEmpty()
                || !latchedStrategyListings.isEmpty();
    }

    /**
     * Reports every scope that is currently killed or latched, so orders whose cancels the venue refused can be
     * cancelled again. OMS thread only.
     */
    public void forEachKilledScope(final KillHandler handler) {
        if (applied.stopsEverything()) {
            handler.onEverythingKilled();
            return;
        }
        reportingTo = handler;
        applied.killedStrategies.forEachInt(reportStrategyKill);
        latchedStrategies.forEachInt(reportStrategyKill);
        applied.killedExchanges.forEachInt(reportExchangeKill);
        applied.killedListings.forEachInt(reportListingKill);
        applied.killedStrategyExchanges.forEachLong(reportStrategyExchangeKill);
        latchedStrategyExchanges.forEachLong(reportStrategyExchangeKill);
        applied.killedStrategyListings.forEachLong(reportStrategyListingKill);
        latchedStrategyListings.forEachLong(reportStrategyListingKill);
    }

    private void reportNewStrategyKill(final int strategyId) {
        if (!reportingAgainst.killedStrategies.contains(strategyId)) {
            reportingTo.onStrategyKilled(strategyId);
        }
    }

    private void reportNewExchangeKill(final int exchangeId) {
        if (!reportingAgainst.killedExchanges.contains(exchangeId)) {
            reportingTo.onExchangeKilled(exchangeId);
        }
    }

    private void reportNewListingKill(final int listingId) {
        if (!reportingAgainst.killedListings.contains(listingId)) {
            reportingTo.onListingKilled(listingId);
        }
    }

    private void reportNewStrategyExchangeKill(final long key) {
        if (!reportingAgainst.killedStrategyExchanges.contains(key)) {
            reportStrategyExchangeKill(key);
        }
    }

    private void reportNewStrategyListingKill(final long key) {
        if (!reportingAgainst.killedStrategyListings.contains(key)) {
            reportStrategyListingKill(key);
        }
    }

    private void reportStrategyKill(final int strategyId) {
        reportingTo.onStrategyKilled(strategyId);
    }

    private void reportExchangeKill(final int exchangeId) {
        reportingTo.onExchangeKilled(exchangeId);
    }

    private void reportListingKill(final int listingId) {
        reportingTo.onListingKilled(listingId);
    }

    private void reportStrategyExchangeKill(final long key) {
        reportingTo.onStrategyExchangeKilled(RiskEngineSnapshot.pairStrategy(key), RiskEngineSnapshot.pairScope(key));
    }

    private void reportStrategyListingKill(final long key) {
        reportingTo.onStrategyListingKilled(RiskEngineSnapshot.pairStrategy(key), RiskEngineSnapshot.pairScope(key));
    }

    /** Receives killed scopes. */
    public interface KillHandler {
        void onEverythingKilled();

        void onStrategyKilled(int strategyId);

        void onExchangeKilled(int exchangeId);

        void onListingKilled(int listingId);

        void onStrategyExchangeKilled(int strategyId, int exchangeId);

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
            final int listingId,
            final int exchangeId) {
        final RiskEngineSnapshot s = snapshot.get();
        if (stale) {
            haltCause = HALT_STALE;
            return RejectReason.HALTED;
        }
        if (s.unguarded) {
            haltCause = HALT_UNGUARDED;
            return RejectReason.HALTED;
        }
        if (s.blocks(strategyId, listingId, exchangeId)) {
            haltCause = HALT_KILLED;
            return RejectReason.HALTED;
        }
        if (isLatched(strategyId, exchangeId, listingId)) {
            haltCause = HALT_LATCHED;
            return RejectReason.HALTED;
        }
        if (sellsMoreThanHeld(order, positions, orders, strategyId, listingId)) {
            violatedPolicyId = 0;
            return RejectReason.RISK_LIMIT_EXCEEDED;
        }
        return passesOrderGroups(s.orderGroups, order, positions, orders, strategyId, listingId, exchangeId)
                ? null
                : RejectReason.RISK_LIMIT_EXCEEDED;
    }

    private boolean passesOrderGroups(
            final ScopedGroups<OrderPolicyGroup> groups,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int strategyId,
            final int listingId,
            final int exchangeId) {
        return checkOrderGroup(groups.global, order, positions, orders, strategyId, listingId)
                && checkOrderGroup(groups.exchange(exchangeId), order, positions, orders, strategyId, listingId)
                && checkOrderGroup(groups.strategy(strategyId), order, positions, orders, strategyId, listingId)
                && checkOrderGroup(
                        groups.strategyExchange(strategyId, exchangeId),
                        order,
                        positions,
                        orders,
                        strategyId,
                        listingId)
                && checkOrderGroup(
                        groups.strategyListing(strategyId, listingId), order, positions, orders, strategyId, listingId)
                && checkOrderGroup(groups.listing(listingId), order, positions, orders, strategyId, listingId);
    }

    /**
     * Where several strategies share one account's holdings, a strategy selling more than it holds would sell
     * another's, so on such a listing it may sell only what it holds less what it is already selling. Always on,
     * whatever policies are set.
     */
    private boolean sellsMoreThanHeld(
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int strategyId,
            final int listingId) {
        if (order.decoder.side() != Side.Ask || !directory.isLongOnly(listingId)) {
            return false;
        }
        final Position position = positions.getStrategyPosition(strategyId, listingId);
        final long held = position == null ? 0 : position.netQuantity;
        long selling = position == null ? 0 : position.leavesSellQty;
        // A modify is checked as the replacement for its order, whose leaves are already counted.
        final TrackedOrder replaced = orders.getOrder(order.getClientOidCounter());
        if (replaced != null) {
            selling -= replaced.workingQty();
        }
        return order.decoder.size() > held - selling;
    }

    /** The registry id of the policy behind the last {@link RejectReason#RISK_LIMIT_EXCEEDED}; 0 if it has none. */
    public int violatedPolicyId() {
        return violatedPolicyId;
    }

    /** The registry id of the policy behind the last breach {@link #checkMarketPolicies} found; 0 if it has none. */
    public int breachedPolicyId() {
        return breachedPolicyId;
    }

    /**
     * Why the last {@link RejectReason#HALTED} was: {@link #HALT_STALE}, {@link #HALT_KILLED}, {@link #HALT_LATCHED}
     * or {@link #HALT_UNGUARDED}.
     */
    public int haltCause() {
        return haltCause;
    }

    /**
     * Checks market-risk policies after a fill, a mark move or a venue reject on {@code listingId}. Each policy knows
     * whether it judges that listing or the strategy's total. Returns true if any policy is violated; then
     * {@link #latchBreach} halts the strategy in that policy's scope.
     */
    public boolean checkMarketPolicies(
            final int strategyId,
            final int listingId,
            final int exchangeId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final ScopedGroups<MarketPolicyGroup> groups = snapshot.get().marketGroups;
        return isMarketGroupViolated(groups.global, strategyId, listingId, positions, orders, 0, 0)
                || isMarketGroupViolated(
                        groups.exchange(exchangeId), strategyId, listingId, positions, orders, exchangeId, 0)
                || isMarketGroupViolated(groups.strategy(strategyId), strategyId, listingId, positions, orders, 0, 0)
                || isMarketGroupViolated(
                        groups.strategyExchange(strategyId, exchangeId),
                        strategyId,
                        listingId,
                        positions,
                        orders,
                        exchangeId,
                        0)
                || isMarketGroupViolated(
                        groups.strategyListing(strategyId, listingId),
                        strategyId,
                        listingId,
                        positions,
                        orders,
                        0,
                        listingId)
                || isMarketGroupViolated(
                        groups.listing(listingId), strategyId, listingId, positions, orders, 0, listingId);
    }

    /**
     * How far past the touch a market order may fill: the tightest {@code MARKET_ORDER_SLIPPAGE} that applies, or 0
     * (the touch itself) when none does.
     */
    public long marketSlippage(final int strategyId, final int listingId, final int exchangeId) {
        final ScopedGroups<SlippageLimit> limits = snapshot.get().slippageLimits;
        long tightest = limits.global.maxSlippage;
        tightest = tighter(tightest, limits.exchange(exchangeId));
        tightest = tighter(tightest, limits.strategy(strategyId));
        tightest = tighter(tightest, limits.strategyExchange(strategyId, exchangeId));
        tightest = tighter(tightest, limits.strategyListing(strategyId, listingId));
        tightest = tighter(tightest, limits.listing(listingId));
        return tightest == SlippageLimit.NONE ? 0 : tightest;
    }

    private static long tighter(final long tightest, final SlippageLimit limit) {
        return limit == null ? tightest : Math.min(tightest, limit.maxSlippage);
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

    /** {@code scopeExchangeId} and {@code scopeListingId} are the scope a breach in this group latches. */
    private boolean isMarketGroupViolated(
            final MarketPolicyGroup group,
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int scopeExchangeId,
            final int scopeListingId) {
        if (group == null) {
            return false;
        }
        for (int i = 0; i < group.count; i++) {
            final MarketRiskPolicy policy = group.policies[i];
            if (policy.isViolated(strategyId, listingId, positions, orders)) {
                breachedPolicyId = group.policyIds[i];
                breachedCause =
                        policy instanceof MaxConsecutiveRejectsPolicy ? CAUSE_REJECT_STREAK : CAUSE_MARKET_LIMIT;
                breachedExchangeId = scopeExchangeId;
                breachedListingId = scopeListingId;
                return true;
            }
        }
        return false;
    }
}
