package group.gnometrading.oms.risk;

import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;

public interface RiskPolicy {

    RiskCheckResult validate(OmsOrder order, PositionTracker positions, OrderStateManager orders);
}
