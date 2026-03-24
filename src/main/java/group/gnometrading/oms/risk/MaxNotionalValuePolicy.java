package group.gnometrading.oms.risk;

import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;

public final class MaxNotionalValuePolicy implements RiskPolicy {

    private final long maxNotionalValue;

    public MaxNotionalValuePolicy(long maxNotionalValue) {
        this.maxNotionalValue = maxNotionalValue;
    }

    @Override
    public RiskCheckResult validate(OmsOrder order, PositionTracker positions, OrderStateManager orders) {
        long notional = order.price() * order.size();
        if (notional > maxNotionalValue) {
            return new RiskCheckResult.Rejected(
                    "MaxNotionalValue", String.format("Order notional %d exceeds max %d", notional, maxNotionalValue));
        }
        return new RiskCheckResult.Approved();
    }
}
