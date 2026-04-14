package group.gnometrading.oms.risk;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Evaluates risk policies on the order and market-time hot paths.
 *
 * <p>In production, a {@link RiskSyncAgent} running on a dedicated thread periodically fetches
 * policies and publishes a new {@link RiskEngineSnapshot} via {@link #publishSnapshot}. The hot
 * path reads the snapshot with a single volatile read — no sync work, no I/O.
 *
 * <p>For tests and backtest, use {@link #RiskEngine(OrderPolicyGroup, MarketPolicyGroup)} to
 * supply pre-built global groups directly.
 */
public final class RiskEngine {

    private static final int MAX_HALTED_STRATEGY_ID = 1024;

    private final AtomicReference<RiskEngineSnapshot> snapshot;
    private final boolean[] haltedStrategies = new boolean[MAX_HALTED_STRATEGY_ID];

    /**
     * Test/backtest constructor. Uses the provided groups as the global order and market groups.
     * No dynamic sync is expected.
     */
    @VisibleForTesting
    public RiskEngine(final OrderPolicyGroup globalOrderGroup, final MarketPolicyGroup globalMarketGroup) {
        final RiskEngineSnapshot initial = new RiskEngineSnapshot();
        initial.globalOrderGroup.count = globalOrderGroup.count;
        System.arraycopy(globalOrderGroup.policies, 0, initial.globalOrderGroup.policies, 0, globalOrderGroup.count);
        initial.globalMarketGroup.count = globalMarketGroup.count;
        System.arraycopy(globalMarketGroup.policies, 0, initial.globalMarketGroup.policies, 0, globalMarketGroup.count);
        this.snapshot = new AtomicReference<>(initial);
    }

    /**
     * Production constructor. A {@link RiskSyncAgent} is expected to call
     * {@link #publishSnapshot} periodically.
     */
    public RiskEngine() {
        this.snapshot = new AtomicReference<>(new RiskEngineSnapshot());
    }

    /**
     * Creates a RiskEngine pre-loaded with the given order-time policies in the global order
     * group. Intended for backtest and test use where no {@link RiskSyncAgent} is running.
     */
    public static RiskEngine withOrderPolicies(final OrderRiskPolicy... policies) {
        final OrderPolicyGroup orderGroup = new OrderPolicyGroup(Math.max(policies.length, 1));
        for (final OrderRiskPolicy p : policies) {
            orderGroup.policies[orderGroup.count++] = p;
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
            orderGroup.policies[orderGroup.count++] = p;
        }
        final MarketPolicyGroup marketGroup = new MarketPolicyGroup(Math.max(marketPolicies.length, 1));
        for (final MarketRiskPolicy p : marketPolicies) {
            marketGroup.policies[marketGroup.count++] = p;
        }
        return new RiskEngine(orderGroup, marketGroup);
    }

    void publishSnapshot(final RiskEngineSnapshot newSnapshot) {
        snapshot.set(newSnapshot);
    }

    public boolean isStrategyHalted(final int strategyId) {
        if (strategyId < 0 || strategyId >= MAX_HALTED_STRATEGY_ID) {
            return false;
        }
        return haltedStrategies[strategyId];
    }

    public void haltStrategy(final int strategyId) {
        if (strategyId >= 0 && strategyId < MAX_HALTED_STRATEGY_ID) {
            haltedStrategies[strategyId] = true;
        }
    }

    public void resumeStrategy(final int strategyId) {
        if (strategyId >= 0 && strategyId < MAX_HALTED_STRATEGY_ID) {
            haltedStrategies[strategyId] = false;
        }
    }

    public boolean check(
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders,
            final int strategyId,
            final int listingId) {
        if (isStrategyHalted(strategyId)) {
            return false;
        }
        final RiskEngineSnapshot s = snapshot.get();
        return checkOrderGroup(s.globalOrderGroup, order, positions, orders, strategyId, listingId)
                && checkOrderGroup(s.getStrategyOrderGroup(strategyId), order, positions, orders, strategyId, listingId)
                && checkOrderGroup(s.getListingOrderGroup(listingId), order, positions, orders, strategyId, listingId);
    }

    /**
     * Checks market-time policies after a position update. Returns true if any policy is violated.
     */
    public boolean checkMarketPolicies(
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final RiskEngineSnapshot s = snapshot.get();
        return isMarketGroupViolated(s.globalMarketGroup, strategyId, listingId, positions, orders)
                || isMarketGroupViolated(s.getStrategyMarketGroup(strategyId), strategyId, listingId, positions, orders)
                || isMarketGroupViolated(s.getListingMarketGroup(listingId), strategyId, listingId, positions, orders);
    }

    private static boolean checkOrderGroup(
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
                return false;
            }
        }
        return true;
    }

    private static boolean isMarketGroupViolated(
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
                return true;
            }
        }
        return false;
    }
}
