package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import org.junit.jupiter.api.Test;

class MarketOrderPricingTest {

    private static final long CENT = Statics.PRICE_SCALING_FACTOR / 100;
    private static final long MILLI = Statics.PRICE_SCALING_FACTOR / 1000;

    @Test
    void aBuyPaysUpToTheAskPlusTheSlippageAndASellDownToTheBidLessIt() {
        assertEquals(63 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 3 * CENT, CENT, true));
        assertEquals(37 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 3 * CENT, CENT, true));
    }

    @Test
    void noSlippageTakesAtTheTouch() {
        assertEquals(60 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 0, CENT, true));
        assertEquals(40 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 0, CENT, true));
    }

    @Test
    void slippageOffTheTickIsRoundedTowardTheTouchSoTheAllowanceIsNeverExceeded() {
        assertEquals(62 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 25 * MILLI, CENT, true));
        assertEquals(38 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 25 * MILLI, CENT, true));
    }

    @Test
    void aTouchFinerThanTheTickIsStillCrossed() {
        // Kalshi's tapered books quote in tenths of a cent near the ends, finer than the listing's coarsest tick.
        assertEquals(6 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 55 * MILLI, 0, CENT, true));
        assertEquals(5 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 55 * MILLI, 0, CENT, true));
    }

    @Test
    void aBinaryListingIsKeptStrictlyBetweenNothingAndADollar() {
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 97 * CENT, 10 * CENT, CENT, true));
        assertEquals(CENT, MarketOrderPricing.worstPrice(Side.Ask, 3 * CENT, 10 * CENT, CENT, true));
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 97 * CENT, Long.MAX_VALUE, CENT, true));
    }

    @Test
    void anotherListingMayBuyPastADollarButNeverSellsBelowOneTick() {
        final long dollar = Statics.PRICE_SCALING_FACTOR;
        assertEquals(105 * dollar, MarketOrderPricing.worstPrice(Side.Bid, 100 * dollar, 5 * dollar, CENT, false));
        assertEquals(CENT, MarketOrderPricing.worstPrice(Side.Ask, 2 * dollar, 5 * dollar, CENT, false));
        assertEquals(Long.MAX_VALUE, MarketOrderPricing.worstPrice(Side.Bid, 100 * dollar, Long.MAX_VALUE, 0, false));
    }

    @Test
    void aListingWithNoTickIsPricedExactly() {
        assertEquals(60 * CENT + 7, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 7, 0, true));
        assertEquals(40 * CENT - 7, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 7, 0, true));
    }
}
