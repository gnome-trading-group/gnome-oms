package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.GnomeString;

/**
 * Rejects an order if filling it, together with every other open order on the same side, could take the net
 * position beyond {@code maxPosition} (size units) in either direction.
 */
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
        final long netQty = pos != null ? pos.netQuantity : 0;
        final boolean bid = order.decoder.side() == Side.Bid;
        long openSameSide = pos == null ? 0 : (bid ? pos.leavesBuyQty : pos.leavesSellQty);
        // A modify is checked as the replacement for its order, whose leaves are already counted.
        final TrackedOrder replaced = orders.getOrder(order.getClientOidCounter());
        if (replaced != null) {
            openSameSide -= replaced.getLeavesQty();
        }
        // Every open order on this side could fill, so the worst case counts all of them.
        final long worstCase =
                bid ? netQty + openSameSide + order.decoder.size() : netQty - openSameSide - order.decoder.size();
        return Math.abs(worstCase) > maxPosition;
    }
}
