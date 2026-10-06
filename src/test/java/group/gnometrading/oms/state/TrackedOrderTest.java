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
        assertEquals(50L, order.workingQty());
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
        assertEquals(0, order.workingQty());
    }

    // --- applyExecutionReport ---

    @Test
    void applyNew_setsState_andVenueLeavesDoNotChangeWhatIsWorking() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 8, OrderExecutionReportDecoder.feeNullValue());

        assertEquals(OrderState.NEW, order.getState());
        assertEquals(10L, order.workingQty());
        assertEquals(0L, order.getFilledQty());
        assertFalse(order.getState().isTerminal());
    }

    @Test
    void applySinglePartialFill_accumulatesCostAndQty() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);

        assertEquals(OrderState.PARTIALLY_FILLED, order.getState());
        assertEquals(3L, order.getFilledQty());
        assertEquals(7L, order.workingQty());
    }

    @Test
    void applyMultiplePartialFills_accumulateCorrectly() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.PARTIAL_FILL, 4, 110, 7, 3, 0);

        assertEquals(7L, order.getFilledQty());
        assertEquals(3L, order.workingQty());
    }

    @Test
    void applyFill_setsTerminalStateAndZeroesLeaves() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.FILL, 7, 105, 10, 0, 0);

        assertEquals(OrderState.FILLED, order.getState());
        assertEquals(10L, order.getFilledQty());
        assertEquals(0L, order.workingQty());
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
        assertEquals(0L, order.workingQty());
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
        assertEquals(20L, order.workingQty());
    }

    @Test
    void applyRejectAndExpire_zeroLeaves() {
        initOrder(10L);
        applyReport(ExecType.REJECT, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        assertEquals(0L, order.workingQty());

        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        applyReport(ExecType.EXPIRE, 0, 0, 0, 10, OrderExecutionReportDecoder.feeNullValue());
        assertEquals(0L, order.workingQty());
    }

    @Test
    void applyNew_withNullLeaves_keepsWorkingTheOrderSize() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, OrderExecutionReportDecoder.leavesQtyNullValue(), 0);

        assertEquals(OrderState.NEW, order.getState());
        assertEquals(10L, order.workingQty());
    }

    @Test
    void applyPartialFill_withNullCumulative_addsTheFillToWhatWasFilled() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 3, 100, 3, 7, 0);
        applyReport(ExecType.PARTIAL_FILL, 2, 100, OrderExecutionReportDecoder.cumulativeQtyNullValue(), 5, 0);

        assertEquals(5L, order.getFilledQty());
    }

    @Test
    void applyPartialFill_withNullLeaves_worksTheRestOfTheOrder() {
        initOrder(10L);
        applyReport(ExecType.PARTIAL_FILL, 4, 100, 4, OrderExecutionReportDecoder.leavesQtyNullValue(), 0);

        assertEquals(6L, order.workingQty());
    }

    @Test
    void sizesBeyondTheOld32BitLimitAreKept() {
        long size = 50_000L * 1_000_000L;
        initOrder(size);
        applyReport(ExecType.PARTIAL_FILL, size / 2, 600_000_000L, size / 2, size / 2, 0);

        assertEquals(size / 2, order.getFilledQty());
        assertEquals(size / 2, order.workingQty());
    }

    // --- helpers ---

    @Test
    void pendingModifyCountsTheLargerSizeUntilTheVenueAnswers() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, 0);
        order.modify(100L, 5L);
        applyReport(ExecType.PARTIAL_FILL, 7, 100, 7, 3, 0); // the original 10 filled 7 before the modify landed

        assertEquals(3L, order.workingQty(), "the original order may still have 3 working");

        applyReport(ExecType.NEW, 0, 0, 7, 0, 0); // the modify to 5 is confirmed: already more than filled
        assertEquals(0L, order.workingQty());
    }

    @Test
    void refusedModifyRestoresTheOriginalSize() {
        initOrder(10L);
        applyReport(ExecType.NEW, 0, 0, 0, 10, 0);
        order.modify(100L, 20L);
        assertEquals(20L, order.workingQty());

        applyReport(ExecType.CANCEL_REJECT, 0, 0, 0, 10, 0);
        assertEquals(10L, order.workingQty());
    }

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
