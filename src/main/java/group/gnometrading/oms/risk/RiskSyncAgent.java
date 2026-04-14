package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.risk.PolicyScope;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.risk.RiskPolicyRecord;
import group.gnometrading.utils.Schedule;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;

/**
 * Background agent that periodically fetches risk policies from the registry and publishes
 * a pre-built {@link RiskEngineSnapshot} to {@link RiskEngine} via an atomic reference.
 *
 * <p>Runs on its own thread via {@link group.gnometrading.concurrent.GnomeAgentRunner}.
 * The OMS hot path reads only from the published snapshot — no sync work, no I/O.
 */
public final class RiskSyncAgent implements GnomeAgent {

    private final RiskMaster riskMaster;
    private final RiskEngine riskEngine;
    private final Schedule refreshSchedule;
    private final IntHashMap<Configurable> policyCache;
    private final PolicyFactory policyFactory;

    public RiskSyncAgent(
            final RiskMaster riskMaster,
            final RiskEngine riskEngine,
            final EpochClock clock,
            final Duration refreshInterval) {
        this(riskMaster, riskEngine, clock, refreshInterval, null, null);
    }

    public RiskSyncAgent(
            final RiskMaster riskMaster,
            final RiskEngine riskEngine,
            final EpochClock clock,
            final Duration refreshInterval,
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry) {
        this.riskMaster = riskMaster;
        this.riskEngine = riskEngine;
        this.refreshSchedule = new Schedule(clock, refreshInterval.toMillis(), this::refreshAndPublish);
        this.policyCache = new IntHashMap<>();
        this.policyFactory = new PolicyFactory(priceBuffer, priceSlotRegistry);
    }

    @Override
    public void onStart() {
        refreshSchedule.start();
    }

    @Override
    public int doWork() {
        refreshSchedule.check();
        return 0;
    }

    private void refreshAndPublish() {
        riskMaster.refresh();
        // TODO: this allocates a new snapshot each cycle (every 5s), producing garbage.
        // Consider pooling snapshots with a proper reset mechanism to eliminate GC pressure.
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        buildSnapshot(riskMaster, snapshot);
        riskEngine.publishSnapshot(snapshot);
    }

    private void buildSnapshot(final RiskMaster source, final RiskEngineSnapshot snapshot) {
        final int count = source.getPolicyCount();
        for (int i = 0; i < count; i++) {
            final RiskPolicyRecord record = source.getRecord(i);
            if (!record.enabled) {
                continue;
            }

            final RiskPolicyType type = RiskPolicyType.fromString(record.policyType);
            if (type == null) {
                throw new IllegalStateException("Unknown risk policy type: " + record.policyType);
            }

            if (type.category() == RiskPolicyType.Category.ORDER) {
                addOrderPolicy(
                        snapshot,
                        record.scope,
                        record.strategyId,
                        record.listingId,
                        getOrCreateOrderPolicy(record.policyId, type, record));
            } else {
                addMarketPolicy(
                        snapshot,
                        record.scope,
                        record.strategyId,
                        record.listingId,
                        getOrCreateMarketPolicy(record.policyId, type, record));
            }
        }
    }

    private OrderRiskPolicy getOrCreateOrderPolicy(
            final int policyId, final RiskPolicyType type, final RiskPolicyRecord record) {
        final Configurable cached = policyCache.get(policyId);
        if (cached instanceof OrderRiskPolicy) {
            cached.reconfigure(record.parametersJson);
            return (OrderRiskPolicy) cached;
        }
        final OrderRiskPolicy policy = (OrderRiskPolicy) policyFactory.create(type);
        policy.reconfigure(record.parametersJson);
        policyCache.put(policyId, policy);
        return policy;
    }

    private MarketRiskPolicy getOrCreateMarketPolicy(
            final int policyId, final RiskPolicyType type, final RiskPolicyRecord record) {
        final Configurable cached = policyCache.get(policyId);
        if (cached instanceof MarketRiskPolicy) {
            cached.reconfigure(record.parametersJson);
            return (MarketRiskPolicy) cached;
        }
        final MarketRiskPolicy policy = (MarketRiskPolicy) policyFactory.create(type);
        policy.reconfigure(record.parametersJson);
        policyCache.put(policyId, policy);
        return policy;
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
