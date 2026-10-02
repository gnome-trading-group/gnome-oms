package group.gnometrading.oms.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.OmsTestHarness;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackedOrderTest {

    private TrackedOrder order;

    @BeforeEach
    void setUp() {
        order = new TrackedOrder();
    }

    // --- init ---

    @Test
    void init_copiesAllFieldsFromOrder() {
        Order src = OmsTestHarness.buildOrder(7, 42L, 1, 99, Side.Bid, 1000L, 50L);
        order.init(src);

        assertTrue(order.isActive());
        assertEquals(1, order.getExchangeId());
        assertEquals(99, order.getSecurityId());
        assertEquals(7, order.getStrategyId());
        assertEquals(42L, order.getClientOidCounter());
        assertEquals(Side.Bid, order.getSide());
        assertEquals(1000L, order.getPrice());
        assertEquals(50L, order.getSize());
        assertEquals(OrderType.LIMIT, order.getOrderType());
        assertEquals(TimeInForce.GOOD_TILL_CANCELED, order.getTimeInForce());
        assertEquals(OrderState.PENDING_NEW, order.getState());
        assertEquals(0L, order.getFilledQty());
        assertEquals(50L, order.getLeavesQty());
    }

    @Test
    void reset_clearsAllFields() {
        Order src = OmsTestHarness.buildOrder(7, 1L, 1, 99, Side.Ask, 2000L, 10L);
        order.init(src);
        order.reset();

        assertFalse(order.isActive());
        assertEquals(0, order.getExchangeId());
        assertEquals(0, order.getSecurityId());
        assertEquals(0, order.getFilledQty());
        assertEquals(0, order.getLeavesQty());
    }

    // --- applyExecutionReport ---

    @Test
    void applyNew_setsStateAndLeavesQty() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 8, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.NEW, order.getState());
        assertEquals(8L, order.getLeavesQty());
        assertEquals(0L, order.getFilledQty());
        assertFalse(order.getState().isTerminal());
    }

    @Test
    void applySinglePartialFill_accumulatesCostAndQty() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);

        assertEquals(OrderState.PARTIALLY_FILLED, order.getState());
        assertEquals(3L, order.getFilledQty());
        assertEquals(7L, order.getLeavesQty());
    }

    @Test
    void applyMultiplePartialFills_accumulateCorrectly() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.PARTIAL_FILL, 4, 110, 7, 3, 0);

        assertEquals(7L, order.getFilledQty());
        assertEquals(3L, order.getLeavesQty());
    }

    @Test
    void applyFill_setsTerminalStateAndZeroesLeaves() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.FILL, 7, 105, 10, 0, 0);

        assertEquals(OrderState.FILLED, order.getState());
        assertEquals(10L, order.getFilledQty());
        assertEquals(0L, order.getLeavesQty());
        assertTrue(order.getState().isTerminal());
    }

    @Test
    void applyCancel_setsTerminalState_andZeroesLeaves() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        applyReport(ExecType.CANCEL, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.CANCELED, order.getState());
        assertTrue(order.getState().isTerminal());
        // Nothing is working once cancelled; the OMS reads the leaves before applying the report.
        assertEquals(0L, order.getLeavesQty());
    }

    @Test
    void applyReject_setsTerminalState() {
        initOrder(10L);
        applyReport(ExecType.REJECT, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.REJECTED, order.getState());
        assertTrue(order.getState().isTerminal());
    }

    @Test
    void applyExpire_setsTerminalState() {
        initOrder(10L);
        applyReport(ExecType.EXPIRE, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.EXPIRED, order.getState());
        assertTrue(order.getState().isTerminal());
    }

    @Test
    void applyCancelReject_noStateChange() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        applyReport(ExecType.CANCEL_REJECT, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.NEW, order.getState());
        assertFalse(order.getState().isTerminal());
    }

    @Test
    void modify_updatesPriceSizeAndLeavesQty() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        order.modify(200L, 20L);

        assertEquals(200L, order.getPrice());
        assertEquals(20L, order.getSize());
        assertEquals(20L, order.getLeavesQty());
    }

    @Test
    void applyRejectAndExpire_zeroLeaves() {
        initOrder(10L);
        applyReport(ExecType.REJECT, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        assertEquals(0L, order.getLeavesQty());

        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        applyReport(ExecType.EXPIRE, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        assertEquals(0L, order.getLeavesQty());
    }

    @Test
    void applyNew_withNullLeaves_keepsCurrentLeaves() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, OrderExecutionReportDecoder.leavesQtyNullValue(), 0);

        assertEquals(OrderState.NEW, order.getState());
        assertEquals(10L, order.getLeavesQty());
    }

    @Test
    void applyPartialFill_withNullCumulative_addsTheFillToWhatWasFilled() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.PARTIAL_FILL, 2, 100, OrderExecutionReportDecoder.cumulativeQtyNullValue(), 5, 0);

        assertEquals(5L, order.getFilledQty());
    }

    @Test
    void applyPartialFill_withNullLeaves_derivesThemFromTheOrderSize() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 4, 100, 4, OrderExecutionReportDecoder.leavesQtyNullValue(), 0);

        assertEquals(6L, order.getLeavesQty());
    }

    @Test
    void sizesBeyondTheOld32BitLimitAreKept() {
        long size = 50_000L * 1_000_000L;
        initOrder(size);
        applyReport(ExecType.PARTIAL_FILL, size / 2, 600_000_000L, size / 2, size / 2, 0);

        assertEquals(size / 2, order.getFilledQty());
        assertEquals(size / 2, order.getLeavesQty());
    }

    // --- helpers ---

    private void initOrder(long size) {
        Order src = OmsTestHarness.buildOrder(7, 1L, 1, 42, Side.Bid, 100L, size);
        order.init(src);
    }

    private void applyReport(ExecType type, long filledQty, long fillPrice, long cumQty, long leavesQty, long fee) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(1L, 7);
        report.encoder
                .exchangeId(1)
                .securityId(42)
                .orderId(0)
                .execType(type)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty(filledQty)
                .fillPrice(fillPrice)
                .cumulativeQty(cumQty)
                .leavesQty(leavesQty)
                .timestampEvent(0)
                .timestampRecv(0)
                .fee(fee);
        report.encoder.flags().clear();
        order.applyExecutionReport(report);
    }
}
