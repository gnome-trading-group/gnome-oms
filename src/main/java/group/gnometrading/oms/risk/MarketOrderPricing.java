package group.gnometrading.oms.risk;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;

/**
 * The limit a market order is sent at. No venue we trade takes a market order without a price (Kalshi dropped
 * them, Polymarket signs every order with one), so the OMS sends an immediate-or-cancel limit at the worst price it
 * will accept: the touch plus the allowed slippage, on the listing's tick.
 */
public final class MarketOrderPricing {

    private MarketOrderPricing() {}

    /**
     * @param touch the best ask for a buy, the best bid for a sell; positive
     * @param tickSize 0 when the listing has no tick
     * @param binary whether the listing pays one or nothing, so every price lies strictly between 0 and 1
     */
    public static long worstPrice(
            final Side side, final long touch, final long slippage, final long tickSize, final boolean binary) {
        final long tick = Math.max(tickSize, 1);
        // Rounded toward the touch so the slippage allowed is never exceeded, but always across it: a touch finer
        // than the listing's tick (Kalshi's tapered books) would otherwise round to a price that can't fill.
        if (side == Side.Bid) {
            final long ceiling = binary ? Statics.PRICE_SCALING_FACTOR - tick : Long.MAX_VALUE;
            final long allowed = slippage >= ceiling - touch ? ceiling : touch + slippage;
            return Math.min(Math.max(floor(allowed, tick), ceil(touch, tick)), ceiling);
        }
        final long allowed = Math.max(touch - slippage, tick);
        return Math.max(Math.min(ceil(allowed, tick), floor(touch, tick)), tick);
    }

    private static long floor(final long price, final long tick) {
        return price - price % tick;
    }

    private static long ceil(final long price, final long tick) {
        final long remainder = price % tick;
        return remainder == 0 ? price : price + tick - remainder;
    }
}
