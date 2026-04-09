package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxPositionPolicyTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;

    @Mock
    private OrderStateManager orders;

    private DefaultPositionTracker positions;
    private Order order;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        order = new Order();
    }

    @Test
    void testNotViolatedWhenNoExistingPositionAndOrderWithinLimit() {
        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Bid).size(5);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testViolatedWhenNoExistingPositionAndOrderExceedsLimit() {
        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Bid).size(11);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testViolatedWhenExistingLongPositionAndBuyExceedsLimit() {
        // Build up netQty = 8 via fills
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 8, 100, 0);

        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Bid).size(5); // abs(8 + 5) = 13 > 10
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testNotViolatedWhenExistingLongPositionAndSellReducesWithinLimit() {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 8, 100, 0);

        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Ask).size(5); // abs(8 - 5) = 3 <= 10
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testViolatedWhenSellCreatesExcessiveShortPosition() {
        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Ask).size(11); // abs(0 - 11) = 11 > 10
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testBoundaryExactlyAtMaxPositionNotViolated() {
        final MaxPositionPolicy policy = new MaxPositionPolicy(10);
        order.encoder.side(Side.Bid).size(10); // abs(0 + 10) = 10, 10 > 10 is false
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void testReconfigureUpdatesMaxPosition() {
        final MaxPositionPolicy policy = new MaxPositionPolicy();
        policy.reconfigure(new ViewString("{\"maxPosition\": 5}"));

        order.encoder.side(Side.Bid).size(6);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));

        policy.reconfigure(new ViewString("{\"maxPosition\": 100}"));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }
}
