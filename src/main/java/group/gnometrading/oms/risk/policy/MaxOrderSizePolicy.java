package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.GnomeString;

public final class MaxOrderSizePolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private long maxOrderSize;

    public MaxOrderSizePolicy() {}

    public MaxOrderSizePolicy(final long maxOrderSize) {
        this.maxOrderSize = maxOrderSize;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxOrderSize = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxOrderSize");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        return order.decoder.size() > maxOrderSize;
    }
}
