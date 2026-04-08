package group.gnometrading.oms.risk;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;

public interface OrderRiskPolicy extends Configurable {

    boolean isViolated(int strategyId, int listingId, Order order, PositionTracker positions, OrderStateManager orders);
}
