package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.GnomeString;
import java.util.function.Consumer;

/**
 * Rejects a new order once the strategy already has {@code maxOpenOrders} orders open: on the order's listing for a
 * listing-scoped limit, across the strategy's listings on one exchange for an exchange-scoped one, or across all its
 * listings otherwise. An order counts from the moment it is sent until the OMS releases it, so orders awaiting an ack
 * or a cancel count too.
 */
public final class MaxOpenOrdersPolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private final PositionScope scope;
    private long maxOpenOrders;
    private int openSum;
    private final Consumer<Position> addOpen = this::addOpen;

    /** {@code listingId} and {@code exchangeId} are the policy's target; 0 is every listing or exchange. */
    public MaxOpenOrdersPolicy(final ListingDirectory directory, final int listingId, final int exchangeId) {
        this.scope = new PositionScope(directory, listingId, exchangeId);
    }

    public MaxOpenOrdersPolicy(
            final ListingDirectory directory, final int listingId, final int exchangeId, final long maxOpenOrders) {
        this(directory, listingId, exchangeId);
        this.maxOpenOrders = maxOpenOrders;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxOpenOrders = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxOpenOrders");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        // A modify replaces an order that is already counted.
        if (orders.getOrder(order.getClientOidCounter()) != null) {
            return false;
        }
        return openOrders(strategyId, listingId, positions) + 1 > maxOpenOrders;
    }

    private int openOrders(final int strategyId, final int listingId, final PositionTracker positions) {
        if (scope.acrossListings()) {
            openSum = 0;
            positions.forEachListingPosition(strategyId, addOpen);
            return openSum;
        }
        final Position position = positions.getStrategyPosition(strategyId, listingId);
        return position == null ? 0 : position.openOrders;
    }

    private void addOpen(final Position position) {
        if (scope.covers(position.listingId)) {
            openSum += position.openOrders;
        }
    }
}
