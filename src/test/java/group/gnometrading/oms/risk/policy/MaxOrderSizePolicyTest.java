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
class MaxOrderSizePolicyTest {

    @Mock
    private PositionTracker positions;

    @Mock
    private OrderStateManager orders;

    private final Order order = new Order();

    @Test
    void testNotViolatedWhenSizeBelowMax() {
        final MaxOrderSizePolicy policy = new MaxOrderSizePolicy(100);
        order.encoder.size(50);
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testNotViolatedWhenSizeEqualsMax() {
        final MaxOrderSizePolicy policy = new MaxOrderSizePolicy(100);
        order.encoder.size(100);
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testViolatedWhenSizeExceedsMax() {
        final MaxOrderSizePolicy policy = new MaxOrderSizePolicy(100);
        order.encoder.size(101);
        assertTrue(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testReconfigureUpdatesMaxOrderSize() {
        final MaxOrderSizePolicy policy = new MaxOrderSizePolicy();
        policy.reconfigure(new ViewString("{\"maxOrderSize\": 50}"));

        order.encoder.size(51);
        assertTrue(policy.isViolated(0, 0, order, positions, orders));

        policy.reconfigure(new ViewString("{\"maxOrderSize\": 200}"));
        assertFalse(policy.isViolated(0, 0, order, positions, orders));
    }
}
