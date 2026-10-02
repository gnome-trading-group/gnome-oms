package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.RingBufferOrderStateManager;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderManagementSystemTest {

    // Quantities in whole units; money then reads as price × units.
    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 100;
    private static final int STRATEGY_ID = 7;

    @Mock
    private SecurityMaster securityMaster;

    private OrderManagementSystem oms;
    private RecordingSink delegate;

    @BeforeEach
    void setUp() {
        RingBufferOrderStateManager orderStateManager = new RingBufferOrderStateManager(64);
        DefaultPositionTracker positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(8));
        RiskEngine riskEngine = new RiskEngine();
        oms = new OrderManagementSystem(
                new NullLogger(),
                orderStateManager,
                positionTracker,
                riskEngine,
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1));
        delegate = new RecordingSink();

        Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "KALSHI", "Test", "US", null),
                new Security(SECURITY_ID, "SYM", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "SYM",
                "SYM");
        lenient().when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
        lenient().when(securityMaster.getListing(LISTING_ID)).thenReturn(listing);
    }

    // --- lotSize constraint ---

    @Test
    void testNewOrderWithValidLotSizeIsForwarded() {
        stubSpec(10, 0);
        submitIntent(100L, 10L); // 10 % 10 == 0
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderWithInvalidLotSizeIsRejected() {
        stubSpec(10, 0);
        submitIntent(100L, 7L); // 7 % 10 != 0
        assertEquals(0, delegate.newOrders.size());
    }

    @Test
    void testNewOrderWithLotSizeZeroIsNotConstrained() {
        stubSpec(0, 0);
        submitIntent(100L, 7L);
        assertEquals(1, delegate.newOrders.size());
    }

    // --- minNotional constraint ---

    @Test
    void testNewOrderAboveMinNotionalIsForwarded() {
        stubSpec(0, 1000);
        submitIntent(100L, 10L * UNIT); // notional = 100 * 10 units = 1000 >= 1000
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderBelowMinNotionalIsRejected() {
        stubSpec(0, 1000);
        submitIntent(10L, 5L * UNIT); // notional = 50 < 1000
        assertEquals(0, delegate.newOrders.size());
    }

    @Test
    void testNewOrderWithMinNotionalZeroIsNotConstrained() {
        stubSpec(0, 0);
        submitIntent(1L, 1L);
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNotionalAboveTheOldOverflowPointPassesMinNotional() {
        // $1 minimum; 20,000 contracts at $0.60 used to overflow price * size and fail the check.
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        submitIntent(600_000_000L, 20_000L * UNIT);
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testMinNotionalIsNotLoosenedByIntegerDivision() {
        // 0.5 units at $1.999999999 is just under a $1 minimum; dividing the minimum by the size first let it pass.
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        submitIntent(1_999_999_999L, UNIT / 2);
        assertEquals(0, delegate.newOrders.size());
    }

    // --- both constraints ---

    @Test
    void testNewOrderPassingBothConstraintsIsForwarded() {
        stubSpec(10 * UNIT, 1000);
        submitIntent(100L, 10L * UNIT); // a whole lot, and notional 1000 >= 1000
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderFailingBothConstraintsIsRejected() {
        stubSpec(10 * UNIT, 1000);
        submitIntent(1L, 3L * UNIT); // not a whole lot, and notional 3 < 1000
        assertEquals(0, delegate.newOrders.size());
    }

    // --- modify constraints ---

    @Test
    void testModifyWithInvalidLotSizeIsRejected() {
        stubSpec(10, 0);
        submitIntent(100L, 10L);
        assertEquals(1, delegate.newOrders.size());

        ackOrder(delegate.newOrders.get(0));

        // Now slot is LIVE — a new intent with different size triggers onModify
        submitIntent(100L, 7L); // 7 % 10 != 0
        assertEquals(0, delegate.modifies.size());
    }

    @Test
    void testModifyWithValidLotSizeIsForwarded() {
        stubSpec(10, 0);
        submitIntent(100L, 10L);
        assertEquals(1, delegate.newOrders.size());

        ackOrder(delegate.newOrders.get(0));

        submitIntent(100L, 20L); // 20 % 10 == 0, different size → triggers modify
        assertEquals(1, delegate.modifies.size());
    }

    @Test
    void testModifyBelowMinNotionalIsRejected() {
        stubSpec(0, 1000);
        submitIntent(100L, 10L * UNIT); // passes initial constraints
        assertEquals(1, delegate.newOrders.size());

        ackOrder(delegate.newOrders.get(0));

        submitIntent(1L, 1L * UNIT); // notional = 1 < 1000
        assertEquals(0, delegate.modifies.size());
    }

    @Test
    void testCancelRejectForUnknownOrderIsDiscardedSilently() {
        Logger mockLogger = mock(Logger.class);
        OrderManagementSystem testOms = new OrderManagementSystem(
                mockLogger,
                new RingBufferOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(8)),
                new RiskEngine(),
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1));

        OrderExecutionReport cancelReject = new OrderExecutionReport();
        cancelReject.encodeClientOid(1L, STRATEGY_ID);
        cancelReject
                .encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(ExecType.CANCEL_REJECT)
                .filledQty(0)
                .fillPrice(0)
                .cumulativeQty(0)
                .leavesQty(0);
        testOms.processExecutionReport(cancelReject, delegate);

        verify(mockLogger, never()).log(LogMessage.EXEC_REPORT_FOR_UNKNOWN_ORDER, 1L);
        assertEquals(0, delegate.newOrders.size());
    }

    // --- helpers ---

    private void stubSpec(long lotSize, long minNotional) {
        when(securityMaster.getListingSpec(LISTING_ID))
                .thenReturn(new ListingSpec(LISTING_ID, 1, lotSize, minNotional, 1));
    }

    private void submitIntent(long price, long size) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(price)
                .bidSize(size)
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue());
        delegate.clear();
        oms.processIntent(intent, delegate);
    }

    private void ackOrder(Order order) {
        OrderExecutionReport ack = new OrderExecutionReport();
        ack.encodeClientOid(order.getClientOidCounter(), STRATEGY_ID);
        ack.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(ExecType.NEW)
                .filledQty(0)
                .fillPrice(0)
                .cumulativeQty(0)
                .leavesQty(order.decoder.size());
        oms.processExecutionReport(ack, delegate);
        delegate.clear();
    }

    private static final class RecordingSink implements ActionSink {
        final List<Order> newOrders = new ArrayList<>();
        final List<ModifyOrder> modifies = new ArrayList<>();
        final List<CancelOrder> cancels = new ArrayList<>();

        void clear() {
            newOrders.clear();
            modifies.clear();
            cancels.clear();
        }

        @Override
        public void onNewOrder(Order order) {
            newOrders.add(order);
        }

        @Override
        public void onModify(ModifyOrder modify) {
            modifies.add(modify);
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            cancels.add(cancel);
        }
    }
}
