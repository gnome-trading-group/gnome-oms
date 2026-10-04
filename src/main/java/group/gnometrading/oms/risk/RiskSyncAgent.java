package group.gnometrading.oms.risk;

import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.risk.PolicyScope;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.risk.RiskPolicyRecord;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.Schedule;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.EpochClock;

/**
 * Background agent that fetches risk policies from the registry, publishes a pre-built {@link RiskEngineSnapshot}
 * to {@link RiskEngine} whenever they change, and records each successful refresh so the engine can tell when its
 * view has gone stale.
 *
 * <p>Runs on its own thread via {@link group.gnometrading.concurrent.GnomeAgentRunner}. The OMS hot path reads only
 * from the published snapshot — no sync work, no I/O. Nothing here may stop the thread: a failed refresh keeps the
 * last policies, and a policy that can't be built kills its scope rather than trading without it. It also records
 * halts the OMS latched after a market-risk breach as strategy kill switches, so an operator can see and resume them.
 */
public final class RiskSyncAgent implements GnomeAgent {

    private static final long MAX_PARK_MS = 1000L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final String ESCALATION_REASON = "market risk limit breached";

    private final RiskMaster riskMaster;
    private final RiskEngine riskEngine;
    private final EpochClock clock;
    private final Logger logger;
    private final Schedule refreshSchedule;
    private final PolicyFactory policyFactory;

    private final IntHashSet unrecordedHalts = new IntHashSet();
    private final IntHashSet recordedHalts = new IntHashSet();
    private final MessageConsumer<RiskEngine.LatchedHalt> haltCollector = halt -> unrecordedHalts.add(halt.strategyId);

    private boolean published;
    private long publishedHash;
    private long sequence;
    // The first snapshot carrying every halt in recordedHalts, or -1 until one is published.
    private long confirmationSequence = -1;

    public RiskSyncAgent(
            final RiskMaster riskMaster,
            final RiskEngine riskEngine,
            final EpochClock clock,
            final Duration refreshInterval,
            final Logger logger) {
        this(riskMaster, riskEngine, clock, refreshInterval, logger, null, null);
    }

    public RiskSyncAgent(
            final RiskMaster riskMaster,
            final RiskEngine riskEngine,
            final EpochClock clock,
            final Duration refreshInterval,
            final Logger logger,
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry) {
        this.riskMaster = riskMaster;
        this.riskEngine = riskEngine;
        this.clock = clock;
        this.logger = logger;
        this.refreshSchedule = new Schedule(clock, refreshInterval.toMillis(), this::refreshAndPublish);
        this.policyFactory = new PolicyFactory(priceBuffer, priceSlotRegistry);
    }

    @Override
    public void onStart() {
        refreshSchedule.start();
        refreshSchedule.forceTrigger();
    }

    @Override
    public int doWork() {
        refreshSchedule.check();
        recordLatchedHalts();
        long remainingMs = refreshSchedule.millisUntilNext();
        if (remainingMs > 0) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(remainingMs, MAX_PARK_MS)));
        }
        return 0;
    }

    private void refreshAndPublish() {
        try {
            riskMaster.refresh();
        } catch (RuntimeException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Risk policy refresh failed, keeping the last policies: %s", e);
            return;
        }
        final long hash = hashPolicies(riskMaster);
        if (confirmationSequence >= 0 && riskEngine.appliedSequence() >= confirmationSequence) {
            recordedHalts.clear();
            confirmationSequence = -1;
        }
        // Recorded halts must reach the OMS even if nothing else changed, or a strategy an operator resumed before the
        // kill propagated would stay latched. They ride every snapshot until the OMS has applied one, since it can
        // skip snapshots published faster than it looks.
        if (!published || hash != publishedHash || !recordedHalts.isEmpty()) {
            final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
            buildSnapshot(riskMaster, snapshot);
            snapshot.sequence = ++sequence;
            snapshot.confirmedHalts.addAll(recordedHalts);
            if (!recordedHalts.isEmpty() && confirmationSequence < 0) {
                confirmationSequence = snapshot.sequence;
            }
            published = true;
            publishedHash = hash;
            riskEngine.publishSnapshot(snapshot);
        }
        riskEngine.recordRefresh(clock.time());
    }

    /** A halt the registry didn't accept stays pending and is retried on the next pass. */
    private void recordLatchedHalts() {
        riskEngine.drainLatchedHalts(haltCollector);
        if (unrecordedHalts.isEmpty()) {
            return;
        }
        final IntHashSet.IntIterator pending = unrecordedHalts.iterator();
        while (pending.hasNext()) {
            final int strategyId = pending.nextValue();
            try {
                riskMaster.requestHalt(strategyId, ESCALATION_REASON);
            } catch (RuntimeException e) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Recording halt of strategy %d failed: %s", strategyId, e);
                continue;
            }
            pending.remove();
            recordedHalts.add(strategyId);
            confirmationSequence = -1;
            refreshSchedule.forceTrigger();
        }
    }

    private static long hashPolicies(final RiskMaster source) {
        final int count = source.getPolicyCount();
        long hash = mix(FNV_OFFSET, count);
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            hash = mix(hash, record.policyId);
            hash = mix(hash, record.policyType);
            hash = mix(hash, record.scope == null ? -1 : record.scope.ordinal());
            hash = mix(hash, record.strategyId);
            hash = mix(hash, record.listingId);
            hash = mix(hash, record.enabled ? 1 : 0);
            hash = mix(hash, record.parametersJson);
        }
        return hash;
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static long mix(final long hash, final GnomeString value) {
        long result = mix(hash, value.length());
        for (int i = 0; i < value.length(); i++) {
            result = mix(result, value.byteAt(i));
        }
        return result;
    }

    /** Kill switches first, so no policy that fails to build can keep a kill from applying. */
    private void buildSnapshot(final RiskMaster source, final RiskEngineSnapshot snapshot) {
        final int count = source.getPolicyCount();
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            if (record.enabled && RiskPolicyType.fromString(record.policyType) == RiskPolicyType.KILL_SWITCH) {
                addKill(snapshot, record.scope, record.strategyId, record.listingId);
            }
        }
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            if (record.enabled) {
                addPolicy(snapshot, record);
            }
        }
    }

    /**
     * A policy that can't be built — an unknown type, or parameters it can't read — kills its scope instead: trading
     * on without a limit someone configured is the unsafe choice, and a kill is visible to an operator.
     */
    private void addPolicy(final RiskEngineSnapshot snapshot, final RiskPolicyRecord record) {
        final RiskPolicyType type = RiskPolicyType.fromString(record.policyType);
        if (type == RiskPolicyType.KILL_SWITCH) {
            return;
        }
        try {
            if (type == null) {
                throw new IllegalStateException("unknown risk policy type " + record.policyType);
            }
            if (record.scope == null) {
                throw new IllegalStateException("unreadable scope");
            }
            final Configurable policy = policyFactory.create(type);
            policy.reconfigure(record.parametersJson);
            if (type.category() == RiskPolicyType.Category.ORDER) {
                addOrderPolicy(snapshot, record.scope, record.strategyId, record.listingId, (OrderRiskPolicy) policy);
            } else {
                addMarketPolicy(snapshot, record.scope, record.strategyId, record.listingId, (MarketRiskPolicy) policy);
            }
        } catch (RuntimeException e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Risk policy %d can't be applied, killing its scope instead: %s",
                    record.policyId,
                    e);
            addKill(snapshot, record.scope, record.strategyId, record.listingId);
        }
    }

    // A kill whose scope can't be read stops everything rather than nothing.
    private static void addKill(
            final RiskEngineSnapshot snapshot, final PolicyScope scope, final int strategyId, final int listingId) {
        if (scope == PolicyScope.STRATEGY) {
            snapshot.killedStrategies.add(strategyId);
        } else if (scope == PolicyScope.LISTING) {
            snapshot.killedListings.add(listingId);
        } else {
            snapshot.globalKill = true;
        }
    }

    private static void addOrderPolicy(
            final RiskEngineSnapshot snapshot,
            final PolicyScope scope,
            final int strategyId,
            final int listingId,
            final OrderRiskPolicy policy) {
        if (scope == PolicyScope.GLOBAL) {
            snapshot.globalOrderGroup.policies[snapshot.globalOrderGroup.count++] = policy;
        } else if (scope == PolicyScope.STRATEGY) {
            OrderPolicyGroup group = snapshot.strategyOrderGroups.get(strategyId);
            if (group == null) {
                group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
                snapshot.strategyOrderGroups.put(strategyId, group);
            }
            group.policies[group.count++] = policy;
        } else {
            OrderPolicyGroup group = snapshot.listingOrderGroups.get(listingId);
            if (group == null) {
                group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
                snapshot.listingOrderGroups.put(listingId, group);
            }
            group.policies[group.count++] = policy;
        }
    }

    private static void addMarketPolicy(
            final RiskEngineSnapshot snapshot,
            final PolicyScope scope,
            final int strategyId,
            final int listingId,
            final MarketRiskPolicy policy) {
        if (scope == PolicyScope.GLOBAL) {
            snapshot.globalMarketGroup.policies[snapshot.globalMarketGroup.count++] = policy;
        } else if (scope == PolicyScope.STRATEGY) {
            MarketPolicyGroup group = snapshot.strategyMarketGroups.get(strategyId);
            if (group == null) {
                group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
                snapshot.strategyMarketGroups.put(strategyId, group);
            }
            group.policies[group.count++] = policy;
        } else {
            MarketPolicyGroup group = snapshot.listingMarketGroups.get(listingId);
            if (group == null) {
                group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
                snapshot.listingMarketGroups.put(listingId, group);
            }
            group.policies[group.count++] = policy;
        }
    }
}
