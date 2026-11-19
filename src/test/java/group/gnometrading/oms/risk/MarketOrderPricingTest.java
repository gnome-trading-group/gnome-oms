package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.PriceGrid;
import org.junit.jupiter.api.Test;

class MarketOrderPricingTest {

    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long CENT = DOLLAR / 100;
    private static final long MILLI = DOLLAR / 1000;
    private static final PriceGrid CENTS = PriceGrid.single(CENT, DOLLAR);
    private static final PriceGrid TAPERED =
            PriceGrid.of(new long[] {0, 10 * CENT, 90 * CENT}, new long[] {10 * CENT, 90 * CENT, DOLLAR}, new long[] {
                MILLI, CENT, MILLI
            });
    private static final PriceGrid OPEN_CENTS = PriceGrid.single(CENT, PriceGrid.OPEN);

    @Test
    void aBuyPaysUpToTheAskPlusTheSlippageAndASellDownToTheBidLessIt() {
        assertEquals(63 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 3 * CENT, CENTS));
        assertEquals(37 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 3 * CENT, CENTS));
    }

    @Test
    void noSlippageTakesAtTheTouch() {
        assertEquals(60 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 0, CENTS));
        assertEquals(40 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 0, CENTS));
    }

    @Test
    void slippageOffTheGridIsRoundedTowardTheTouchSoTheAllowanceIsNeverExceeded() {
        assertEquals(62 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 60 * CENT, 25 * MILLI, CENTS));
        assertEquals(38 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 40 * CENT, 25 * MILLI, CENTS));
    }

    @Test
    void aTouchBetweenGridPointsIsStillCrossed() {
        assertEquals(6 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 55 * MILLI, 0, CENTS));
        assertEquals(5 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 55 * MILLI, 0, CENTS));
    }

    @Test
    void aTaperedGridPricesTheTailsInTenthsOfACentAndTheMiddleInCents() {
        assertEquals(53 * MILLI, MarketOrderPricing.worstPrice(Side.Bid, 50 * MILLI, 3 * MILLI, TAPERED));
        assertEquals(47 * MILLI, MarketOrderPricing.worstPrice(Side.Ask, 50 * MILLI, 3 * MILLI, TAPERED));
        assertEquals(50 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 495 * MILLI, 3 * MILLI, TAPERED));
        assertEquals(49 * CENT, MarketOrderPricing.worstPrice(Side.Ask, 495 * MILLI, 3 * MILLI, TAPERED));
    }

    @Test
    void slippageAcrossABandEdgeLandsOnTheBandItReaches() {
        // From 9.8c, 5c up crosses into the cent band: 14.8c floors to 14c.
        assertEquals(14 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 98 * MILLI, 5 * CENT, TAPERED));
        // From 10c, 4.8c down is 5.2c, a point of the tenth-of-a-cent band below.
        assertEquals(52 * MILLI, MarketOrderPricing.worstPrice(Side.Ask, 10 * CENT, 48 * MILLI, TAPERED));
    }

    @Test
    void aBinaryListingIsKeptStrictlyBetweenNothingAndADollar() {
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 97 * CENT, 10 * CENT, CENTS));
        assertEquals(CENT, MarketOrderPricing.worstPrice(Side.Ask, 3 * CENT, 10 * CENT, CENTS));
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Bid, 97 * CENT, Long.MAX_VALUE, CENTS));
        assertEquals(999 * MILLI, MarketOrderPricing.worstPrice(Side.Bid, 97 * CENT, Long.MAX_VALUE, TAPERED));
        assertEquals(MILLI, MarketOrderPricing.worstPrice(Side.Ask, 3 * CENT, Long.MAX_VALUE, TAPERED));
    }

    @Test
    void aBrokenBookAtOrAboveTheGridsEndStillPricesOnTheGrid() {
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Ask, DOLLAR, 0, CENTS));
        assertEquals(999 * MILLI, MarketOrderPricing.worstPrice(Side.Ask, 2 * DOLLAR, 0, TAPERED));
        assertEquals(99 * CENT, MarketOrderPricing.worstPrice(Side.Bid, DOLLAR, 0, CENTS));
    }

    @Test
    void anOpenGridMayBuyPastADollarButNeverSellsBelowItsFirstStep() {
        assertEquals(105 * DOLLAR, MarketOrderPricing.worstPrice(Side.Bid, 100 * DOLLAR, 5 * DOLLAR, OPEN_CENTS));
        assertEquals(CENT, MarketOrderPricing.worstPrice(Side.Ask, 2 * DOLLAR, 5 * DOLLAR, OPEN_CENTS));
        assertEquals(
                OPEN_CENTS.max(), MarketOrderPricing.worstPrice(Side.Bid, 100 * DOLLAR, Long.MAX_VALUE, OPEN_CENTS));
    }
}
