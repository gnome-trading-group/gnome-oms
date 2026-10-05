package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxOpenOrdersPolicyTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;
    private static final int OTHER_LISTING_ID = 200;

    @Mock
    private OrderStateManager orders;

    private DefaultPositionTracker positions;
    private final Order order = new Order();

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        order.encodeClientOid(99, STRATEGY_ID);
    }

    @Test
    void aListingLimitCountsOnlyThatListing() {
        final MaxOpenOrdersPolicy policy = new MaxOpenOrdersPolicy(false, 2);
        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        positions.addOpenOrder(STRATEGY_ID, OTHER_LISTING_ID);
        positions.addOpenOrder(STRATEGY_ID, OTHER_LISTING_ID);

        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
        assertTrue(policy.isViolated(STRATEGY_ID, OTHER_LISTING_ID, order, positions, orders));
    }

    @Test
    void aStrategyLimitCountsEveryListing() {
        final MaxOpenOrdersPolicy policy = new MaxOpenOrdersPolicy(true, 2);
        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        assertFalse(policy.isViolated(STRATEGY_ID, OTHER_LISTING_ID, order, positions, orders));

        positions.addOpenOrder(STRATEGY_ID, OTHER_LISTING_ID);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
        assertFalse(policy.isViolated(STRATEGY_ID + 1, LISTING_ID, order, positions, orders), "other strategies");
    }

    @Test
    void aReleasedOrderFreesItsPlace() {
        final MaxOpenOrdersPolicy policy = new MaxOpenOrdersPolicy(true, 1);
        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));

        positions.removeOpenOrder(STRATEGY_ID, LISTING_ID);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void aModifyOfAnOpenOrderIsNotANewOne() {
        final MaxOpenOrdersPolicy policy = new MaxOpenOrdersPolicy(true, 1);
        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        when(orders.getOrder(99)).thenReturn(mock(TrackedOrder.class));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }

    @Test
    void reconfigureSetsTheLimit() {
        final MaxOpenOrdersPolicy policy = new MaxOpenOrdersPolicy(true);
        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        policy.reconfigure(new ViewString("{\"maxOpenOrders\": 1}"));
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
        policy.reconfigure(new ViewString("{\"maxOpenOrders\": 2}"));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, order, positions, orders));
    }
}
