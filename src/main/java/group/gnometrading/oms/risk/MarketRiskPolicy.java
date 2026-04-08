package group.gnometrading.oms.risk;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;

public interface MarketRiskPolicy extends Configurable {

    boolean isViolated(int strategyId, int listingId, PositionTracker positions, OrderStateManager orders);
}
