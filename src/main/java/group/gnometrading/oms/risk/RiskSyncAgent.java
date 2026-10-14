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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochClock;

/**
 * Background agent that fetches risk policies from the registry, publishes a pre-built {@link RiskEngineSnapshot}
 * to {@link RiskEngine} whenever they change, and records each successful refresh so the engine can tell when its
 * view has gone stale.
 *
 * <p>Runs on its own thread via {@link group.gnometrading.concurrent.GnomeAgentRunner}. The OMS hot path reads only
 * from the published snapshot — no sync work, no I/O. Nothing here may stop the thread: a failed refresh keeps the
 * last policies, and a policy that can't be built kills its target rather than trading without it. It also records
 * halts the OMS latched as kill switches on the same scope, so an operator can see and resume them.
 *
 * <p>A live session fails closed: unless every listing it trades is covered by each policy in
 * {@link #REQUIRED_TYPES}, nothing may trade, since a limit nobody set is no limit at all.
 */
public final class RiskSyncAgent implements GnomeAgent {

    private static final long MAX_PARK_MS = 1000L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** The policies a live session must have on every listing, in the order LogMessage.RISK_UNGUARDED names them. */
    static final RiskPolicyType[] REQUIRED_TYPES = {
        RiskPolicyType.MAX_COMMITTED_CAPITAL,
        RiskPolicyType.MAX_TOTAL_PNL_LOSS,
        RiskPolicyType.MAX_POSITION,
        RiskPolicyType.MAX_CONSECUTIVE_REJECTS
    };

    private final RiskMaster riskMaster;
    private final RiskEngine riskEngine;
    private final EpochClock clock;
    private final Logger logger;
    private final Schedule refreshSchedule;
    private final PolicyFactory policyFactory;
    private final ListingDirectory directory;
    private final int strategyId;
    private final boolean live;
    // Which of the master's records built into a policy in the snapshot being built.
    private final boolean[] built = new boolean[RiskMaster.MAX_POLICIES];

    private final List<PendingHalt> unrecordedHalts = new ArrayList<>();
    private final IntHashSet recordedStrategyHalts = new IntHashSet();
    private final LongHashSet recordedStrategyExchangeHalts = new LongHashSet();
    private final LongHashSet recordedStrategyListingHalts = new LongHashSet();
    private final MessageConsumer<RiskEngine.LatchedHalt> haltCollector =
            halt -> unrecordedHalts.add(new PendingHalt(halt));

    private boolean published;
    private long publishedHash;
    private long sequence;
    // The first snapshot carrying every recorded halt, or -1 until one is published.
    private long confirmationSequence = -1;

    /**
     * Policies are built for the listings in {@code riskEngine}'s {@link RiskEngine#directory()}, which is also what
     * a {@code live} session must cover; {@code strategyId} is the strategy this session runs.
     */
    public RiskSyncAgent(
            final RiskMaster riskMaster,
            final RiskEngine riskEngine,
            final EpochClock clock,
            final Duration refreshInterval,
            final Logger logger,
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final int strategyId,
            final boolean live) {
        this.riskMaster = riskMaster;
        this.riskEngine = riskEngine;
        this.clock = clock;
        this.logger = logger;
        this.refreshSchedule = new Schedule(clock, refreshInterval.toMillis(), this::refreshAndPublish);
        this.directory = riskEngine.directory();
        this.policyFactory = new PolicyFactory(priceBuffer, priceSlotRegistry, directory, riskEngine.rejectStreaks());
        this.strategyId = strategyId;
        this.live = live;
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
            clearRecordedHalts();
            confirmationSequence = -1;
        }
        // Recorded halts must reach the OMS even if nothing else changed, or a strategy an operator resumed before the
        // kill propagated would stay latched. They ride every snapshot until the OMS has applied one, since it can
        // skip snapshots published faster than it looks.
        final boolean policiesChanged = !published || hash != publishedHash;
        if (policiesChanged || hasRecordedHalts()) {
            final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
            buildSnapshot(riskMaster, snapshot);
            if (live) {
                checkCoverage(riskMaster, snapshot, policiesChanged);
            }
            snapshot.sequence = ++sequence;
            snapshot.confirmedStrategyHalts.addAll(recordedStrategyHalts);
            snapshot.confirmedStrategyExchangeHalts.addAll(recordedStrategyExchangeHalts);
            snapshot.confirmedStrategyListingHalts.addAll(recordedStrategyListingHalts);
            if (hasRecordedHalts() && confirmationSequence < 0) {
                confirmationSequence = snapshot.sequence;
            }
            published = true;
            publishedHash = hash;
            riskEngine.publishSnapshot(snapshot);
        }
        riskEngine.recordRefresh(clock.time());
    }

    private boolean hasRecordedHalts() {
        return !recordedStrategyHalts.isEmpty()
                || !recordedStrategyExchangeHalts.isEmpty()
                || !recordedStrategyListingHalts.isEmpty();
    }

    private void clearRecordedHalts() {
        recordedStrategyHalts.clear();
        recordedStrategyExchangeHalts.clear();
        recordedStrategyListingHalts.clear();
    }

    /** A halt the registry didn't accept stays pending and is retried on the next pass. */
    private void recordLatchedHalts() {
        riskEngine.drainLatchedHalts(haltCollector);
        final Iterator<PendingHalt> pending = unrecordedHalts.iterator();
        while (pending.hasNext()) {
            final PendingHalt halt = pending.next();
            try {
                riskMaster.requestHalt(halt.strategyId, halt.exchangeId, halt.listingId, halt.reason());
            } catch (RuntimeException e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Recording halt of strategy %d (exchange %d, listing %d) failed: %s",
                        halt.strategyId,
                        halt.exchangeId,
                        halt.listingId,
                        e);
                continue;
            }
            pending.remove();
            if (halt.listingId != 0) {
                recordedStrategyListingHalts.add(RiskEngineSnapshot.pairKey(halt.strategyId, halt.listingId));
            } else if (halt.exchangeId != 0) {
                recordedStrategyExchangeHalts.add(RiskEngineSnapshot.pairKey(halt.strategyId, halt.exchangeId));
            } else {
                recordedStrategyHalts.add(halt.strategyId);
            }
            confirmationSequence = -1;
            refreshSchedule.forceTrigger();
        }
    }

    /** A latched halt copied off the OMS's ring, held until the registry accepts it. */
    private static final class PendingHalt {
        final int strategyId;
        final int exchangeId;
        final int listingId;
        final int cause;
        final int policyId;
        final long rejects;

        PendingHalt(final RiskEngine.LatchedHalt halt) {
            this.strategyId = halt.strategyId;
            this.exchangeId = halt.exchangeId;
            this.listingId = halt.listingId;
            this.cause = halt.cause;
            this.policyId = halt.policyId;
            this.rejects = halt.rejects;
        }

        /** What an operator sees as the halt's reason. */
        String reason() {
            final String policy = policyId == 0 ? "" : " (policy " + policyId + ")";
            return switch (cause) {
                case RiskEngine.CAUSE_REJECT_STREAK -> rejects + " consecutive venue rejects" + policy;
                case RiskEngine.CAUSE_LEDGER -> "ledger failing";
                default -> "market risk limit breached" + policy;
            };
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
            hash = mix(hash, record.exchangeId);
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
                snapshot.addKill(record.strategyId, record.listingId, record.exchangeId);
            }
        }
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            built[i] = appliesHere(source, record) && addPolicy(snapshot, record);
        }
    }

    /**
     * Marks the snapshot unguarded unless, for every listing the session trades, each required policy has a row that
     * built and covers it. Logs the first gap when the policies have changed, so it is reported once rather than on
     * every rebuild.
     */
    private void checkCoverage(final RiskMaster source, final RiskEngineSnapshot snapshot, final boolean report) {
        for (int i = 0; i < directory.listingCount(); i++) {
            final int listingId = directory.listingAt(i);
            final int exchangeId = directory.exchangeOf(listingId);
            for (int type = 0; type < REQUIRED_TYPES.length; type++) {
                if (!isCovered(source, REQUIRED_TYPES[type], listingId, exchangeId)) {
                    snapshot.unguarded = true;
                    if (report) {
                        logger.log(LogMessage.RISK_UNGUARDED, strategyId, listingId, type);
                    }
                    return;
                }
            }
        }
    }

    private boolean isCovered(
            final RiskMaster source, final RiskPolicyType type, final int listingId, final int exchangeId) {
        final int count = source.getPolicyCount();
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            if (built[i]
                    && RiskPolicyType.fromString(record.policyType) == type
                    && (record.strategyId == 0 || record.strategyId == strategyId)
                    && (record.listingId == 0 || record.listingId == listingId)
                    && (record.exchangeId == 0 || record.exchangeId == exchangeId)) {
                return true;
            }
        }
        return false;
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
     *
     * @return whether the record built into a policy
     */
    private boolean addPolicy(final RiskEngineSnapshot snapshot, final RiskPolicyRecord record) {
        final RiskPolicyType type = RiskPolicyType.fromString(record.policyType);
        if (type == RiskPolicyType.KILL_SWITCH) {
            return false;
        }
        try {
            if (type == null) {
                throw new IllegalStateException("unknown risk policy type " + record.policyType);
            }
            if (record.strategyId < 0 || record.listingId < 0 || record.exchangeId < 0) {
                throw new IllegalStateException("unreadable target");
            }
            final Configurable policy = policyFactory.create(type, record.listingId, record.exchangeId);
            policy.reconfigure(record.parametersJson);
            snapshot.addPolicy(record.strategyId, record.listingId, record.exchangeId, policy, record.policyId);
            return true;
        } catch (RuntimeException e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Risk policy %d can't be applied, killing its target instead: %s",
                    record.policyId,
                    e);
            snapshot.addKill(record.strategyId, record.listingId, record.exchangeId);
            return false;
        }
    }
}
