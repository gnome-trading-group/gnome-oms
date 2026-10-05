package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxTotalPnlLossPolicyTest {

    // Quantities in whole units; money then reads as price × units.
    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;
    private static final int OTHER_LISTING_ID = 200;

    @Mock
    private OrderStateManager orders;

    private DefaultPositionTracker positions;
    private SharedPriceBuffer priceBuffer;
    private PriceSlotRegistry priceSlotRegistry;
    private int priceSlot;

    @BeforeEach
    void setUp() {
        priceBuffer = new SharedPriceBuffer(8);
        priceSlotRegistry = new PriceSlotRegistry(8);
        priceSlot = priceSlotRegistry.register(LISTING_ID);
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        positions.registerSlot(STRATEGY_ID, LISTING_ID);
    }

    // --- no position ---

    @Test
    void notViolated_whenNoPosition() {
        final MaxTotalPnlLossPolicy policy = pair(100L);
        priceBuffer.writeTrade(priceSlot, 150L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- no mark price ---

    @Test
    void notViolated_whenNoMarkPrice() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        // markPrice == 0 (never written)
        final MaxTotalPnlLossPolicy policy = pair(100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_whenListingNotRegisteredInPriceRegistry() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        final PriceSlotRegistry emptyRegistry = new PriceSlotRegistry(8);
        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, emptyRegistry, false, 100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- long position ---

    @Test
    void notViolated_longPosition_profiting() {
        // Long 10 @ avg 100, mark = 120 -> unrealizedPnl = 10 * (120 - 100) = 200
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 120L);

        final MaxTotalPnlLossPolicy policy = pair(500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void violated_longPosition_totalLossExceedsMax() {
        // Long 10 @ avg 100, mark = 50 -> unrealizedPnl = 10 * (50 - 100) = -500
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 50L);

        final MaxTotalPnlLossPolicy policy = pair(499L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_longPosition_totalLossExactlyAtMax() {
        // unrealizedPnl = 10 * (50 - 100) = -500, maxLoss = 500 -> -500 < -500 is false
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 50L);

        final MaxTotalPnlLossPolicy policy = pair(500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- short position ---

    @Test
    void violated_shortPosition_markRisesAboveEntry() {
        // Short -10 @ avg 100, mark = 160 -> unrealizedPnl = -10 * (160 - 100) = -600
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 160L);

        final MaxTotalPnlLossPolicy policy = pair(500L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_shortPosition_markFallsBelowEntry() {
        // Short -10 @ avg 100, mark = 80 -> unrealizedPnl = -10 * (80 - 100) = 200
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 80L);

        final MaxTotalPnlLossPolicy policy = pair(500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- combined realized + unrealized ---

    @Test
    void violated_whenCombinedRealizedAndUnrealizedExceedsMax() {
        // Realize -400 loss on first partial close, then hold 5 long with mark moving against
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 5 * UNIT, 60, 0);
        // realizedPnl = 5 * (60 - 100) = -200, remaining long 5 @ avg 100
        // mark = 80 -> unrealizedPnl = 5 * (80 - 100) = -100 -> totalPnl = -300

        priceBuffer.writeTrade(priceSlot, 80L);

        final MaxTotalPnlLossPolicy policy = pair(299L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- flat position ---

    @Test
    void violated_flatPositionWithRealizedLoss() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10 * UNIT, 50, 0);
        // Flat with realizedPnl = -500; the mark no longer matters.
        priceBuffer.writeTrade(priceSlot, 120L);

        assertTrue(pair(499L).isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(pair(500L).isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- strategy total ---

    @Test
    void strategyTotal_offsettingLegsAreNotABreachButEachLegAloneIs() {
        final int otherSlot = priceSlotRegistry.register(OTHER_LISTING_ID);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, OTHER_LISTING_ID, Side.Ask, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 40L); // long leg: -600
        priceBuffer.writeTrade(otherSlot, 40L); // short leg: +600

        final MaxTotalPnlLossPolicy pair = pair(500L);
        final MaxTotalPnlLossPolicy total = total(500L);
        assertFalse(total.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertTrue(pair.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(pair.isViolated(STRATEGY_ID, OTHER_LISTING_ID, positions, orders));
    }

    @Test
    void strategyTotal_twoLegsEachUnderTheLimitBreachTogether() {
        final int otherSlot = priceSlotRegistry.register(OTHER_LISTING_ID);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, OTHER_LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 60L); // -400
        priceBuffer.writeTrade(otherSlot, 60L); // -400

        final MaxTotalPnlLossPolicy pair = pair(500L);
        final MaxTotalPnlLossPolicy total = total(500L);
        assertTrue(total.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(pair.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(pair.isViolated(STRATEGY_ID, OTHER_LISTING_ID, positions, orders));
    }

    @Test
    void strategyTotal_aLegWithNoMarkIsValuedAtEntryWhileRealizedAndFeesCount() {
        priceSlotRegistry.register(OTHER_LISTING_ID); // never priced
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10 * UNIT, 80, 0); // realized -200
        positions.applyStrategyFill(STRATEGY_ID, OTHER_LISTING_ID, Side.Bid, 10 * UNIT, 100, 50L); // fee 50

        assertTrue(total(249L).isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(total(250L).isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void strategyTotal_aStrategyWithNoPositionsIsNotABreach() {
        assertFalse(total(0L).isViolated(99, LISTING_ID, positions, orders));
    }

    @Test
    void strategyTotal_otherStrategiesDoNotCount() {
        positions.applyStrategyFill(2, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        positions.applyStrategyFill(2, LISTING_ID, Side.Ask, 10 * UNIT, 10, 0); // strategy 2: -900

        assertFalse(total(100L).isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
        assertTrue(total(100L).isViolated(2, LISTING_ID, positions, orders));
    }

    // --- fees ---

    @Test
    void violated_feesContributeToViolation() {
        // Long 10 @ avg 100, mark = 95 → unrealizedPnl = -50 (alone: -50 < -100 is false, not violated)
        // fees of 51 → totalPnl = -50 - 51 = -101 → violated with maxLoss = 100
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 51);
        priceBuffer.writeTrade(priceSlot, 95L);

        final MaxTotalPnlLossPolicy policy = pair(100L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_sameScenarioWithoutFees() {
        // Same position as violated_feesContributeToViolation but fee = 0 → not violated
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 95L);

        final MaxTotalPnlLossPolicy policy = pair(100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- reconfigure ---

    @Test
    void reconfigure_updatesMaxLoss() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 50L);
        // unrealizedPnl = 10 * (50 - 100) = -500

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, false);
        policy.reconfigure(new ViewString("{\"maxLoss\": 499}"));
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));

        policy.reconfigure(new ViewString("{\"maxLoss\": 1000}"));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- mark is the book, not a stale trade ---

    @Test
    void violated_whenBookMidShowsLossDespiteStaleProfitableTrade() {
        // Long 10 @ avg 100; the last trade (150) is stale but the book (40/60, mid 50) shows a 500 loss
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10 * UNIT, 100, 0);
        priceBuffer.writeTrade(priceSlot, 150L);
        priceBuffer.writeQuote(priceSlot, 40L, 60L);

        final MaxTotalPnlLossPolicy policy = pair(400L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    /** Judges the (strategy, listing) position it is asked about, as a listing-scoped limit does. */
    private MaxTotalPnlLossPolicy pair(final long maxLoss) {
        return new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, false, maxLoss);
    }

    /** Judges the strategy's total across its listings, as a global or strategy-scoped limit does. */
    private MaxTotalPnlLossPolicy total(final long maxLoss) {
        return new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, true, maxLoss);
    }
}
