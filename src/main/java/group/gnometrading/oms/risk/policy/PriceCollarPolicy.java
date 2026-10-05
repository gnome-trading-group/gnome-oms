package group.gnometrading.oms.risk.policy;

import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.GnomeString;

/**
 * Rejects a fat-finger order: a buy priced more than {@code maxDeviation} above the mark, or a sell priced more than
 * {@code maxDeviation} below it, in price units (1e9 per dollar). Judged per order, whatever the policy applies to.
 *
 * <p>Only the aggressive side is collared: a passive quote far from the market cannot fill at a bad price. A market
 * order has no price to collar, and a listing with no mark yet has nothing to collar against, so both pass.
 */
public final class PriceCollarPolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private long maxDeviation;

    public PriceCollarPolicy(final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
    }

    public PriceCollarPolicy(
            final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry, final long maxDeviation) {
        this(priceBuffer, priceSlotRegistry);
        this.maxDeviation = maxDeviation;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxDeviation = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxDeviation");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final Order order,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final long price = order.decoder.price();
        if (price == OrderDecoder.priceNullValue()) {
            return false;
        }
        final int slot = priceSlotRegistry.getSlot(listingId);
        if (slot == IntToIntHashMap.MISSING) {
            return false;
        }
        final long mark = priceBuffer.markPrice(slot);
        if (mark == 0) {
            return false;
        }
        return order.decoder.side() == Side.Bid ? price - mark > maxDeviation : mark - price > maxDeviation;
    }
}
