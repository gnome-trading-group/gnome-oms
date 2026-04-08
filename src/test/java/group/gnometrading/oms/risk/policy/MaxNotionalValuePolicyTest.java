package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxNotionalValuePolicyTest {

    @Mock
    private PositionTracker positions;

    @Mock
    private OrderStateManager orders;

    private final Order order = new Order();

    @Test
    void testNotViolatedWhenNotionalBelowMax() {
        final MaxNotionalValuePolicy policy = new MaxNotionalValuePolicy(10000);
        order.encoder.price(100).size(50); // notional = 5000
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testNotViolatedWhenNotionalEqualsMax() {
        final MaxNotionalValuePolicy policy = new MaxNotionalValuePolicy(10000);
        order.encoder.price(100).size(100); // notional = 10000
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testViolatedWhenNotionalExceedsMax() {
        final MaxNotionalValuePolicy policy = new MaxNotionalValuePolicy(10000);
        order.encoder.price(100).size(101); // notional = 10100
        assertTrue(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testReconfigureUpdatesMaxNotionalValue() {
        final MaxNotionalValuePolicy policy = new MaxNotionalValuePolicy();
        policy.reconfigure(new ViewString("{\"maxNotionalValue\": 5000}"));

        order.encoder.price(100).size(51); // notional = 5100
        assertTrue(policy.isViolated(0, 0, order, positions, orders));

        policy.reconfigure(new ViewString("{\"maxNotionalValue\": 20000}"));
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }
}
