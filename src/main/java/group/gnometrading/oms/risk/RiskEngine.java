package group.gnometrading.oms.risk;

import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;

public final class RiskEngine {

    private static final RiskCheckResult APPROVED = new RiskCheckResult.Approved();

    private final RiskPolicy[] policies;

    public RiskEngine(RiskPolicy... policies) {
        this.policies = policies;
    }

    public RiskCheckResult check(OmsOrder order, PositionTracker positions, OrderStateManager orders) {
        for (int i = 0; i < policies.length; i++) {
            RiskCheckResult result = policies[i].validate(order, positions, orders);
            if (result instanceof RiskCheckResult.Rejected) {
                return result;
            }
        }
        return APPROVED;
    }

    public RiskPolicy[] getPolicies() {
        return policies;
    }
}
