package group.gnometrading.oms.risk;

import group.gnometrading.schemas.Side;
import group.gnometrading.sm.PriceGrid;

/**
 * The limit a market order is sent at. No venue we trade takes a market order without a price (Kalshi dropped
 * them, Polymarket signs every order with one), so the OMS sends an immediate-or-cancel limit at the worst price it
 * will accept: the touch plus the allowed slippage, on the listing's price grid.
 */
public final class MarketOrderPricing {

    private MarketOrderPricing() {}

    /** @param touch the best ask for a buy, the best bid for a sell; positive */
    public static long worstPrice(final Side side, final long touch, final long slippage, final PriceGrid grid) {
        // Rounded toward the touch so the slippage allowed is never exceeded, but always across it: a touch between
        // grid points would otherwise round to a price that can't fill.
        if (side == Side.Bid) {
            final long top = grid.max();
            final long allowed = slippage >= top - touch ? top : touch + slippage;
            return Math.min(Math.max(grid.floor(allowed), grid.ceil(touch)), top);
        }
        final long bottom = grid.min();
        final long allowed = Math.max(touch - slippage, bottom);
        return Math.min(Math.max(Math.min(grid.ceil(allowed), grid.floor(touch)), bottom), grid.max());
    }
}
