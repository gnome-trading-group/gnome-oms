package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.oms.action.ActionSink;
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
        oms = new OrderManagementSystem(orderStateManager, positionTracker, riskEngine, securityMaster);
        delegate = new RecordingSink();

        Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "TEST", "US", null),
                new Security(SECURITY_ID, "SYM", 1),
                "SYM",
                "SYM");
        when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
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
        submitIntent(100L, 10L); // notional=1000 >= 1000
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderBelowMinNotionalIsRejected() {
        stubSpec(0, 1000);
        submitIntent(10L, 5L); // notional=50 < 1000
        assertEquals(0, delegate.newOrders.size());
    }

    @Test
    void testNewOrderWithMinNotionalZeroIsNotConstrained() {
        stubSpec(0, 0);
        submitIntent(1L, 1L);
        assertEquals(1, delegate.newOrders.size());
    }

    // --- both constraints ---

    @Test
    void testNewOrderPassingBothConstraintsIsForwarded() {
        stubSpec(10, 1000);
        submitIntent(100L, 10L); // 10%10==0, 100*10=1000>=1000
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderFailingBothConstraintsIsRejected() {
        stubSpec(10, 1000);
        submitIntent(1L, 3L); // 3%10!=0 and 1*3=3<1000
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
        submitIntent(100L, 10L); // passes initial constraints
        assertEquals(1, delegate.newOrders.size());

        ackOrder(delegate.newOrders.get(0));

        submitIntent(1L, 1L); // notional=1 < 1000
        assertEquals(0, delegate.modifies.size());
    }

    // --- helpers ---

    private void stubSpec(long lotSize, long minNotional) {
        when(securityMaster.getListingSpec(LISTING_ID))
                .thenReturn(new ListingSpec(LISTING_ID, 1, lotSize, minNotional));
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
