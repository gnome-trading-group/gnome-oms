package group.gnometrading.oms.risk;

import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
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
 * last policies, and a policy that can't be built kills its target rather than trading without it. It also records
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
            hash = mix(hash, record.sessionId);
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
            if (appliesHere(source, record)
                    && RiskPolicyType.fromString(record.policyType) == RiskPolicyType.KILL_SWITCH) {
                snapshot.addKill(record.strategyId, record.listingId);
            }
        }
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            if (appliesHere(source, record)) {
                addPolicy(snapshot, record);
            }
        }
    }

    /**
     * The registry already sends only this session's rows; another session's row reaching here is skipped rather
     * than applied, since its strategy id alone would make it a kill of every session.
     */
    private static boolean appliesHere(final RiskMaster source, final RiskPolicyRecord record) {
        if (!record.enabled) {
            return false;
        }
        if (record.sessionId.length() == 0) {
            return true;
        }
        final String ownSession = source.sessionId();
        return ownSession != null && record.sessionId.equals(ownSession);
    }

    /**
     * A policy that can't be built — an unknown type, or parameters it can't read — kills its target instead: trading
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
            if (record.strategyId < 0 || record.listingId < 0) {
                throw new IllegalStateException("unreadable target");
            }
            final Configurable policy = policyFactory.create(type, record.listingId == 0);
            policy.reconfigure(record.parametersJson);
            snapshot.addPolicy(record.strategyId, record.listingId, policy);
        } catch (RuntimeException e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Risk policy %d can't be applied, killing its target instead: %s",
                    record.policyId,
                    e);
            snapshot.addKill(record.strategyId, record.listingId);
        }
    }
}
