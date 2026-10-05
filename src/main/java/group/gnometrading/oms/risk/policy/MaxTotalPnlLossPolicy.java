package group.gnometrading.oms.risk.policy;

import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.strings.GnomeString;
import java.util.function.Consumer;

/**
 * Breached when total PnL (realized + unrealized, net of fees) falls below {@code -maxLoss}, in price units (1e9
 * per dollar): one listing's for a listing-scoped limit, or the sum across the strategy's listings otherwise, so a
 * losing leg of a hedged strategy is offset by its winning leg.
 *
 * <p>Unrealized PnL is the notional of {@code netQuantity} at {@code markPrice - avgEntryPrice}, where the
 * mark price is the mid of the book, or the last trade when a side is empty, read from {@link SharedPriceBuffer}.
 * A listing with no mark yet is valued at its average entry: its unrealized PnL counts as zero, while its realized
 * PnL and fees still count.
 */
public final class MaxTotalPnlLossPolicy extends AbstractConfigurablePolicy implements MarketRiskPolicy {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final boolean acrossListings;
    private long maxLoss;
    private long totalSum;
    private final Consumer<Position> addTotal = position -> totalSum += totalPnl(position);

    public MaxTotalPnlLossPolicy(
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final boolean acrossListings) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.acrossListings = acrossListings;
    }

    public MaxTotalPnlLossPolicy(
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final boolean acrossListings,
            final long maxLoss) {
        this(priceBuffer, priceSlotRegistry, acrossListings);
        this.maxLoss = maxLoss;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxLoss = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxLoss");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        if (acrossListings) {
            totalSum = 0;
            positions.forEachListingPosition(strategyId, addTotal);
            return totalSum < -maxLoss;
        }
        final Position pos = positions.getStrategyPosition(strategyId, listingId);
        return pos != null && totalPnl(pos) < -maxLoss;
    }

    private long totalPnl(final Position pos) {
        return pos.realizedPnl + unrealizedPnl(pos) - pos.totalFees;
    }

    private long unrealizedPnl(final Position pos) {
        if (pos.netQuantity == 0) {
            return 0;
        }
        final int slot = priceSlotRegistry.getSlot(pos.listingId);
        if (slot == IntToIntHashMap.MISSING) {
            return 0;
        }
        final long markPrice = priceBuffer.markPrice(slot);
        return markPrice == 0 ? 0 : Position.notional(markPrice - pos.getAvgEntryPrice(), pos.netQuantity);
    }
}
