package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxOrderNotionalPolicyTest {

    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;
    private static final int LISTING_ID = 7;

    @Mock
    private PositionTracker positions;

    @Mock
    private OrderStateManager orders;

    private final Order order = new Order();

    @Test
    void testNotViolatedWhenNotionalBelowMax() {
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy(10_000 * DOLLAR);
        order.encoder.price(100 * DOLLAR).size(50 * UNIT); // $5,000
        assertFalse(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void testNotViolatedWhenNotionalEqualsMax() {
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy(10_000 * DOLLAR);
        order.encoder.price(100 * DOLLAR).size(100 * UNIT); // $10,000
        assertFalse(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void testViolatedWhenNotionalExceedsMax() {
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy(10_000 * DOLLAR);
        order.encoder.price(100 * DOLLAR).size(101 * UNIT); // $10,100
        assertTrue(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void testNotionalPastTheOldOverflowPointIsCheckedExactly() {
        // $50,000: price * size alone would wrap a long, which used to make it look tiny or negative.
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy(1_000_000 * DOLLAR);
        order.encoder.price(DOLLAR / 2).size(100_000 * UNIT);
        assertFalse(policy.isViolated(0, LISTING_ID, order, positions, orders));

        final MaxOrderNotionalPolicy tight = new MaxOrderNotionalPolicy(49_999 * DOLLAR);
        assertTrue(tight.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void testNegativePriceIsValuedByItsMagnitude() {
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy(10_000 * DOLLAR);
        order.encoder.price(-200 * DOLLAR).size(51 * UNIT); // $10,200 of exposure
        assertTrue(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void testReconfigureUpdatesMaxOrderNotional() {
        final MaxOrderNotionalPolicy policy = new MaxOrderNotionalPolicy();
        policy.reconfigure(new ViewString("{\"maxOrderNotional\": " + 5_000 * DOLLAR + "}"));

        order.encoder.price(100 * DOLLAR).size(51 * UNIT); // $5,100
        assertTrue(policy.isViolated(0, LISTING_ID, order, positions, orders));

        policy.reconfigure(new ViewString("{\"maxOrderNotional\": " + 20_000 * DOLLAR + "}"));
        assertFalse(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }
}
