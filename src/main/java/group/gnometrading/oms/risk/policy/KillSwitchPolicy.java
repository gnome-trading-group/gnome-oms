package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.GnomeString;

public final class KillSwitchPolicy implements OrderRiskPolicy {

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        return true;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {}
}
