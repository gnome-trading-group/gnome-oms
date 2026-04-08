package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.GnomeString;

public final class MaxPositionPolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private long maxPosition;

    public MaxPositionPolicy() {}

    public MaxPositionPolicy(final long maxPosition) {
        this.maxPosition = maxPosition;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxPosition = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxPosition");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final Position pos = positions.getStrategyPosition(strategyId, listingId);
        final long currentNetQty = pos != null ? pos.netQuantity : 0;
        final long signedOrderSize = order.decoder.side() == Side.Bid ? order.decoder.size() : -order.decoder.size();
        return Math.abs(currentNetQty + signedOrderSize) > maxPosition;
    }
}
