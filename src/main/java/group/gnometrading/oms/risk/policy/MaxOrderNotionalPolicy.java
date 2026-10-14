package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.GnomeString;

/** Rejects an order whose notional exceeds {@code maxOrderNotional}, in price units (1e9 per dollar). */
public final class MaxOrderNotionalPolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private long maxOrderNotional;

    public MaxOrderNotionalPolicy() {}

    public MaxOrderNotionalPolicy(final long maxOrderNotional) {
        this.maxOrderNotional = maxOrderNotional;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxOrderNotional =
                PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxOrderNotional");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        return Position.notional(Math.abs(order.decoder.price()), order.decoder.size()) > maxOrderNotional;
    }
}
