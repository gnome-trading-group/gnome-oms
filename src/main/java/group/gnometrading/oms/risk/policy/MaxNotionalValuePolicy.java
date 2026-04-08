package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.GnomeString;

public final class MaxNotionalValuePolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private long maxNotionalValue;

    public MaxNotionalValuePolicy() {}

    public MaxNotionalValuePolicy(final long maxNotionalValue) {
        this.maxNotionalValue = maxNotionalValue;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxNotionalValue =
                PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxNotionalValue");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        return order.decoder.price() * order.decoder.size() > maxNotionalValue;
    }
}
