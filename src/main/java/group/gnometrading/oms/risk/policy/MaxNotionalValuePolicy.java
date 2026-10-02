package group.gnometrading.oms.risk.policy;

import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.strings.GnomeString;

/**
 * Rejects an order whose notional exceeds {@code maxNotionalValue}, in price units (1e9 per dollar).
 *
 * <p>A market order has no price, so it is valued at the listing's mark price. With no mark price (none
 * traded yet, or no price buffer, as in backtests) its notional is unknown and the order is rejected.
 */
public final class MaxNotionalValuePolicy extends AbstractConfigurablePolicy implements OrderRiskPolicy {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private long maxNotionalValue;

    public MaxNotionalValuePolicy() {
        this(null, null);
    }

    public MaxNotionalValuePolicy(final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
    }

    public MaxNotionalValuePolicy(final long maxNotionalValue) {
        this(null, null);
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
        final long price = order.decoder.price();
        final long valuationPrice = price == OrderDecoder.priceNullValue() ? markPrice(listingId) : Math.abs(price);
        if (valuationPrice <= 0) {
            return true;
        }
        return Position.notional(valuationPrice, order.decoder.size()) > maxNotionalValue;
    }

    private long markPrice(final int listingId) {
        if (priceBuffer == null || priceSlotRegistry == null) {
            return 0;
        }
        final int slot = priceSlotRegistry.getSlot(listingId);
        return slot == IntToIntHashMap.MISSING ? 0 : priceBuffer.readSpinning(slot);
    }
}
