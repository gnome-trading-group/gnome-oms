package group.gnometrading.oms.risk.policy;

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
class KillSwitchPolicyTest {

    @Mock
    private PositionTracker positions;

    @Mock
    private OrderStateManager orders;

    private final KillSwitchPolicy policy = new KillSwitchPolicy();
    private final Order order = new Order();

    @Test
    void testIsViolatedAlwaysReturnsTrue() {
        assertTrue(policy.isViolated(0, 0, order, positions, orders));
    }

    @Test
    void testReconfigureIsNoOp() {
        policy.reconfigure(new ViewString("{}"));
        assertTrue(policy.isViolated(0, 0, order, positions, orders));
    }
}
