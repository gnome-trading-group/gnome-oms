package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.CommittedCapital;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.GnomeString;
import java.util.function.Consumer;

/**
 * Rejects an order that would take the strategy's {@link CommittedCapital} beyond {@code maxCapital}, in price units
 * (1e9 per dollar): on the order's listing for a listing-scoped limit, across the strategy's listings on one exchange
 * for an exchange-scoped one, or across all its listings otherwise. A modify is judged as the replacement for its
 * order.
 *
 * <p>Only an order that adds to the commitment can break the limit, so one that closes a position, or shrinks a
 * working order, always passes, even while the strategy is over the limit.
 */
public final class MaxCommittedCapitalPolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private final ListingDirectory directory;
    private final PositionScope scope;
    private long maxCapital;
    private long committedSum;
    private final Position hypothetical = new Position();
    private final Consumer<Position> addCommitted = this::addCommitted;

    /** {@code listingId} and {@code exchangeId} are the policy's target; 0 is every listing or exchange. */
    public MaxCommittedCapitalPolicy(final ListingDirectory directory, final int listingId, final int exchangeId) {
        this.directory = directory;
        this.scope = new PositionScope(directory, listingId, exchangeId);
    }

    public MaxCommittedCapitalPolicy(
            final ListingDirectory directory, final int listingId, final int exchangeId, final long maxCapital) {
        this(directory, listingId, exchangeId);
        this.maxCapital = maxCapital;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxCapital = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxCapital");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final boolean binary = directory.isBinary(listingId);
        final long price = order.decoder.price();
        final Position position = positions.getStrategyPosition(strategyId, listingId);
        final long before = committedBefore(strategyId, position, positions, binary);
        final long listingBefore = position == null ? 0 : CommittedCapital.of(binary, position);

        final Side side = order.decoder.side();
        final long qty = order.decoder.size();
        final TrackedOrder replaced = orders.getOrder(order.getClientOidCounter());
        final long qtyChange;
        final long notionalChange;
        if (replaced == null) {
            qtyChange = qty;
            notionalChange = Position.notional(price, qty);
        } else {
            // A modify is checked with its size as what can still execute; the order's own size includes the fills.
            final long newOrderQty = qty + replaced.getFilledQty();
            qtyChange = replaced.workingQtyIfModified(newOrderQty) - replaced.workingQty();
            notionalChange = replaced.workingNotionalIfModified(price, newOrderQty) - replaced.workingNotional();
        }
        final long listingAfter = committedWith(binary, position, side, qtyChange, notionalChange);
        final long after = before - listingBefore + listingAfter;
        return after > maxCapital && after > before;
    }

    /** The listing's committed capital with the order applied, worked out on a copy of its position. */
    private long committedWith(
            final boolean binary,
            final Position position,
            final Side side,
            final long qtyChange,
            final long notionalChange) {
        hypothetical.init(0);
        if (position != null) {
            hypothetical.netQuantity = position.netQuantity;
            hypothetical.totalCost = position.totalCost;
            hypothetical.leavesBuyQty = position.leavesBuyQty;
            hypothetical.leavesBuyNotional = position.leavesBuyNotional;
            hypothetical.leavesSellQty = position.leavesSellQty;
            hypothetical.leavesSellNotional = position.leavesSellNotional;
        }
        hypothetical.adjustLeaves(side, qtyChange, notionalChange);
        return CommittedCapital.of(binary, hypothetical);
    }

    private long committedBefore(
            final int strategyId, final Position position, final PositionTracker positions, final boolean binary) {
        if (!scope.acrossListings()) {
            return position == null ? 0 : CommittedCapital.of(binary, position);
        }
        committedSum = 0;
        positions.forEachListingPosition(strategyId, addCommitted);
        return committedSum;
    }

    private void addCommitted(final Position position) {
        if (scope.covers(position.listingId)) {
            committedSum += CommittedCapital.of(directory.isBinary(position.listingId), position);
        }
    }
}
