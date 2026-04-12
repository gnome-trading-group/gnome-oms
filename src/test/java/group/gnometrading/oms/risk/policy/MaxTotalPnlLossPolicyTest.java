package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxTotalPnlLossPolicyTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;

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
        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 100L);
        priceBuffer.write(priceSlot, 150L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- no mark price ---

    @Test
    void notViolated_whenNoMarkPrice() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        // markPrice == 0 (never written)
        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_whenListingNotRegisteredInPriceRegistry() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        final PriceSlotRegistry emptyRegistry = new PriceSlotRegistry(8);
        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, emptyRegistry, 100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- long position ---

    @Test
    void notViolated_longPosition_profiting() {
        // Long 10 @ avg 100, mark = 120 -> unrealizedPnl = 10 * (120 - 100) = 200
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        priceBuffer.write(priceSlot, 120L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void violated_longPosition_totalLossExceedsMax() {
        // Long 10 @ avg 100, mark = 50 -> unrealizedPnl = 10 * (50 - 100) = -500
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        priceBuffer.write(priceSlot, 50L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 499L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_longPosition_totalLossExactlyAtMax() {
        // unrealizedPnl = 10 * (50 - 100) = -500, maxLoss = 500 -> -500 < -500 is false
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        priceBuffer.write(priceSlot, 50L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- short position ---

    @Test
    void violated_shortPosition_markRisesAboveEntry() {
        // Short -10 @ avg 100, mark = 160 -> unrealizedPnl = -10 * (160 - 100) = -600
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10, 100, 0);
        priceBuffer.write(priceSlot, 160L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 500L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void notViolated_shortPosition_markFallsBelowEntry() {
        // Short -10 @ avg 100, mark = 80 -> unrealizedPnl = -10 * (80 - 100) = 200
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10, 100, 0);
        priceBuffer.write(priceSlot, 80L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 500L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- combined realized + unrealized ---

    @Test
    void violated_whenCombinedRealizedAndUnrealizedExceedsMax() {
        // Realize -400 loss on first partial close, then hold 5 long with mark moving against
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 5, 60, 0);
        // realizedPnl = 5 * (60 - 100) = -200, remaining long 5 @ avg 100
        // mark = 80 -> unrealizedPnl = 5 * (80 - 100) = -100 -> totalPnl = -300

        priceBuffer.write(priceSlot, 80L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 299L);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- flat position ---

    @Test
    void notViolated_flatPosition() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10, 50, 0);
        // position is now flat (netQuantity == 0)
        priceBuffer.write(priceSlot, 120L);

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, 100L);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    // --- reconfigure ---

    @Test
    void reconfigure_updatesMaxLoss() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        priceBuffer.write(priceSlot, 50L);
        // unrealizedPnl = 10 * (50 - 100) = -500

        final MaxTotalPnlLossPolicy policy = new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry);
        policy.reconfigure(new ViewString("{\"maxLoss\": 499}"));
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));

        policy.reconfigure(new ViewString("{\"maxLoss\": 1000}"));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }
}
