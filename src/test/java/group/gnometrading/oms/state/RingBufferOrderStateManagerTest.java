package group.gnometrading.oms.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RingBufferOrderStateManagerTest {

    private static final int CAPACITY = 4;

    private RingBufferOrderStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new RingBufferOrderStateManager(CAPACITY);
    }

    private Order buildOrder(long counter, int strategyId) {
        Order order = new Order();
        order.encodeClientOid(counter, strategyId);
        order.encoder
                .exchangeId((short) 1)
                .securityId(100)
                .price(1000)
                .size(10)
                .side(Side.Bid)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        return order;
    }

    private OrderExecutionReport buildReport(long counter, int strategyId, ExecType execType) {
        return buildReport(counter, strategyId, execType, 0, 0, 0, 0);
    }

    private OrderExecutionReport buildReport(
            long counter,
            int strategyId,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumulativeQty,
            long leavesQty) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(counter, strategyId);
        report.encoder
                .execType(execType)
                .filledQty(filledQty)
                .fillPrice(fillPrice)
                .cumulativeQty(cumulativeQty)
                .leavesQty(leavesQty);
        return report;
    }

    @Test
    void testTrackAndRetrieveOrder() {
        Order order = buildOrder(1, 0);
        TrackedOrder tracked = manager.trackOrder(order);

        assertNotNull(tracked);
        assertEquals(1L, tracked.getClientOidCounter());
        assertEquals(OrderState.PENDING_NEW, tracked.getState());
        assertNotNull(manager.getOrder(1));
    }

    @Test
    void testGetOrderReturnsNullForUnknownCounter() {
        assertNull(manager.getOrder(99));
    }

    @Test
    void testApplyExecutionReportNewAck() {
        manager.trackOrder(buildOrder(1, 0));
        OrderExecutionReport report = buildReport(1, 0, ExecType.NEW, 0, 0, 0, 10);
        TrackedOrder tracked = manager.applyExecutionReport(report);

        assertNotNull(tracked);
        assertEquals(OrderState.NEW, tracked.getState());
        assertEquals(10L, tracked.getLeavesQty());
    }

    @Test
    void testApplyExecutionReportPartialFill() {
        manager.trackOrder(buildOrder(1, 0));
        OrderExecutionReport report = buildReport(1, 0, ExecType.PARTIAL_FILL, 3, 500, 3, 7);
        TrackedOrder tracked = manager.applyExecutionReport(report);

        assertEquals(OrderState.PARTIALLY_FILLED, tracked.getState());
        assertEquals(3L, tracked.getFilledQty());
        assertEquals(7L, tracked.getLeavesQty());
        assertEquals(500L, tracked.getAvgFillPrice());
    }

    @Test
    void testApplyExecutionReportFill() {
        manager.trackOrder(buildOrder(2, 0));
        OrderExecutionReport report = buildReport(2, 0, ExecType.FILL, 10, 1000, 10, 0);
        TrackedOrder tracked = manager.applyExecutionReport(report);

        assertEquals(OrderState.FILLED, tracked.getState());
        assertEquals(10L, tracked.getFilledQty());
        assertEquals(0L, tracked.getLeavesQty());
    }

    @Test
    void testApplyExecutionReportCancel() {
        manager.trackOrder(buildOrder(1, 0));
        TrackedOrder tracked = manager.applyExecutionReport(buildReport(1, 0, ExecType.CANCEL));
        assertEquals(OrderState.CANCELED, tracked.getState());
    }

    @Test
    void testApplyExecutionReportForUnknownCounterReturnsNull() {
        OrderExecutionReport report = buildReport(99, 0, ExecType.CANCEL);
        assertNull(manager.applyExecutionReport(report));
    }

    @Test
    void testReleaseAndReuseSlot() {
        Order order1 = buildOrder(1, 0);
        TrackedOrder tracked = manager.trackOrder(order1);
        manager.applyExecutionReport(buildReport(1, 0, ExecType.CANCEL));
        manager.releaseOrder(tracked);

        assertNull(manager.getOrder(1));

        // counter=5 maps to same slot (5 & 3 == 1)
        Order order2 = buildOrder(5, 0);
        manager.trackOrder(order2);
        assertNull(manager.getOrder(1));
        assertNotNull(manager.getOrder(5));
        assertEquals(5L, manager.getOrder(5).getClientOidCounter());
    }

    @Test
    void testForEachOrderSkipsInactiveSlots() {
        manager.trackOrder(buildOrder(1, 0));
        manager.trackOrder(buildOrder(2, 0));
        manager.trackOrder(buildOrder(3, 0));

        // release counter=2
        TrackedOrder toRelease = manager.getOrder(2);
        manager.releaseOrder(toRelease);

        List<Long> seen = new ArrayList<>();
        manager.forEachOrder(t -> seen.add(t.getClientOidCounter()));

        assertEquals(2, seen.size());
        assertEquals(List.of(1L, 3L), seen);
    }

    @Test
    void testCollisionThrowsWhenSlotStillActive() {
        // capacity=4, so counters 1 and 5 map to slot index 1
        manager.trackOrder(buildOrder(1, 0));

        Order order2 = buildOrder(5, 0);
        assertThrows(IllegalStateException.class, () -> manager.trackOrder(order2));
    }

    @Test
    void testTwoPhaseTerminalPattern() {
        manager.trackOrder(buildOrder(1, 0));

        // apply terminal — slot stays active until releaseOrder
        TrackedOrder tracked = manager.applyExecutionReport(buildReport(1, 0, ExecType.FILL, 10, 1000, 10, 0));
        assertNotNull(tracked);
        assertNotNull(manager.getOrder(1));
        assertEquals(OrderState.FILLED, tracked.getState());

        // release — slot becomes inactive
        manager.releaseOrder(tracked);
        assertNull(manager.getOrder(1));
    }

    @Test
    void testNonPowerOfTwoCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RingBufferOrderStateManager(3));
        assertThrows(IllegalArgumentException.class, () -> new RingBufferOrderStateManager(100));
        assertThrows(IllegalArgumentException.class, () -> new RingBufferOrderStateManager(0));
        assertThrows(IllegalArgumentException.class, () -> new RingBufferOrderStateManager(-1));
    }

    @Test
    void testDefaultCapacityIsUsable() {
        RingBufferOrderStateManager defaultManager = new RingBufferOrderStateManager();
        Order order = buildOrder(1, 0);
        defaultManager.trackOrder(order);
        assertNotNull(defaultManager.getOrder(1));
    }
}
