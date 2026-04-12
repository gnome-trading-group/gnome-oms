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

/**
 * Halts a strategy when its total PnL (realized + unrealized) falls below {@code -maxLoss}.
 *
 * <p>Unrealized PnL is computed as {@code netQuantity * (markPrice - avgEntryPrice)}, where the
 * mark price is the last trade price read from {@link SharedPriceBuffer}. If no mark price is
 * available for a listing, this policy conservatively returns {@code false} (not violated) to
 * avoid false halts on startup.
 */
public final class MaxTotalPnlLossPolicy extends AbstractConfigurablePolicy implements MarketRiskPolicy {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private long maxLoss;

    public MaxTotalPnlLossPolicy(final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
    }

    public MaxTotalPnlLossPolicy(
            final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry, final long maxLoss) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
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
        final Position pos = positions.getStrategyPosition(strategyId, listingId);
        if (pos == null || pos.netQuantity == 0) {
            return false;
        }

        final int slot = priceSlotRegistry.getSlot(listingId);
        if (slot == IntToIntHashMap.MISSING) {
            return false;
        }

        final long markPrice = priceBuffer.readSpinning(slot);
        if (markPrice == 0) {
            return false;
        }

        final long unrealizedPnl = pos.netQuantity * (markPrice - pos.getAvgEntryPrice());
        final long totalPnl = pos.realizedPnl + unrealizedPnl;
        return totalPnl < -maxLoss;
    }
}
