package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import group.gnometrading.oms.ledger.LedgerEvent;
import group.gnometrading.oms.ledger.LedgerEventType;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
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
    private RecordingLogger logger;
    private SharedPriceBuffer priceBuffer;
    private int priceSlot;

    @BeforeEach
    void setUp() {
        PooledOrderStateManager orderStateManager = new PooledOrderStateManager(64);
        DefaultPositionTracker positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(8));
        RiskEngine riskEngine = new RiskEngine();
        priceBuffer = new SharedPriceBuffer(1);
        PriceSlotRegistry priceSlotRegistry = new PriceSlotRegistry(1);
        priceSlot = priceSlotRegistry.register(LISTING_ID);
        logger = new RecordingLogger();
        oms = new OrderManagementSystem(
                logger,
                orderStateManager,
                positionTracker,
                riskEngine,
                securityMaster,
                priceBuffer,
                priceSlotRegistry,
                LedgerSink.NONE,
                () -> 0L);
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

    // --- rejection logging ---

    @Test
    void testAListingRuleRejectionNamesTheOrderAndTheRule() {
        when(securityMaster.getListingSpec(LISTING_ID)).thenReturn(new ListingSpec(LISTING_ID, 10, 0, 0, 1, 0));
        submitIntent(105L, 10L);
        assertEquals(
                List.of("ORDER_REJECTED_EXCHANGE_CONSTRAINTS 1 100 66 105 10 2"),
                logger.linesFor(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS),
                "order 1 on listing 100, a 10 @ 105 bid, broke the tick rule");

        stubSpec(0, 1_000_000_000_000L);
        submitIntent(100L, 10L);
        assertTrue(
                logger.linesFor(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS)
                        .get(1)
                        .endsWith(" 3"),
                "min notional");
    }

    @Test
    void testARiskRejectionNamesTheOrderAndThePolicy() {
        oms = omsWith(RiskEngine.withOrderPolicies(new MaxOrderSizePolicy(5)));
        submitIntent(100L, 10L);
        assertEquals(
                List.of("ORDER_REJECTED_RISK_CHECK 1 100 66 100 10 0"),
                logger.linesFor(LogMessage.ORDER_REJECTED_RISK_CHECK));
    }

    @Test
    void testAHaltIsLoggedWhenItStartsNotForEveryOrderItRefuses() {
        oms = omsWith(RiskEngine.syncedFromRegistry(() -> 0L, java.time.Duration.ofSeconds(30)));
        submitIntent(100L, 10L);
        submitIntent(101L, 10L);
        submitIntent(102L, 10L);
        assertEquals(
                List.of("ORDER_REJECTED_HALTED 1 7 100 " + RiskEngine.HALT_STALE),
                logger.linesFor(LogMessage.ORDER_REJECTED_HALTED));
        assertEquals(List.of(RejectReason.HALTED), delegate.rejects, "the last order was still refused");
    }

    @Test
    void testALossLimitLatchIsLoggedOnceWithItsPolicy() {
        // Any loss at all breaches; the fill's fee alone is one.
        oms = omsWith(RiskEngine.withPolicies(
                new OrderRiskPolicy[0],
                new MarketRiskPolicy[] {new MaxTotalPnlLossPolicy(priceBuffer, new PriceSlotRegistry(1), true, 0L)}));
        submitIntent(100L, 10L);
        final long counter = delegate.newOrders.get(0).getClientOidCounter();
        for (int i = 1; i <= 2; i++) {
            final OrderExecutionReport fill = new OrderExecutionReport();
            fill.encodeClientOid(counter, STRATEGY_ID);
            fill.encoder
                    .exchangeId(EXCHANGE_ID)
                    .securityId(SECURITY_ID)
                    .execType(ExecType.PARTIAL_FILL)
                    .filledQty(1)
                    .fillPrice(100)
                    .cumulativeQty(i)
                    .leavesQty(10 - i)
                    .fee(1);
            oms.processExecutionReport(fill, delegate);
        }

        assertEquals(
                List.of("STRATEGY_LATCHED_LOSS_LIMIT " + STRATEGY_ID + " " + LISTING_ID + " 0"),
                logger.linesFor(LogMessage.STRATEGY_LATCHED_LOSS_LIMIT),
                "the second breach finds the strategy already latched");
    }

    private OrderManagementSystem omsWith(final RiskEngine engine) {
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        slots.register(LISTING_ID);
        return new OrderManagementSystem(
                logger,
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(8)),
                engine,
                securityMaster,
                new SharedPriceBuffer(1),
                slots,
                LedgerSink.NONE,
                () -> 0L);
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

    // --- market order min notional uses the side it would execute against ---

    @Test
    void testMarketBuyIsValuedAtTheAsk() {
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        priceBuffer.writeTrade(priceSlot, Statics.PRICE_SCALING_FACTOR / 100);
        priceBuffer.writeQuote(priceSlot, Statics.PRICE_SCALING_FACTOR / 10, Statics.PRICE_SCALING_FACTOR / 4);
        submitMarketIntent(Side.Bid, 4 * UNIT); // 4 at the $0.25 ask = $1.00
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testMarketSellIsValuedAtTheBid() {
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        priceBuffer.writeQuote(priceSlot, Statics.PRICE_SCALING_FACTOR / 10, Statics.PRICE_SCALING_FACTOR / 4);
        submitMarketIntent(Side.Ask, 4 * UNIT); // 4 at the $0.10 bid = $0.40
        assertEquals(0, delegate.newOrders.size());
    }

    @Test
    void testMarketOrderFallsBackToLastTradeWhenSideEmpty() {
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        priceBuffer.writeTrade(priceSlot, Statics.PRICE_SCALING_FACTOR / 2);
        priceBuffer.writeQuote(priceSlot, Statics.PRICE_SCALING_FACTOR / 10, 0);
        submitMarketIntent(Side.Bid, 2 * UNIT); // no ask; 2 at the $0.50 last trade = $1.00
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testMarketOrderWithNoPriceIsRejected() {
        stubSpec(0, Statics.PRICE_SCALING_FACTOR);
        submitMarketIntent(Side.Bid, 100 * UNIT);
        assertEquals(0, delegate.newOrders.size());
    }

    // --- minSize constraint ---

    @Test
    void testNewOrderAtMinSizeIsForwarded() {
        // Polymarket: 5 shares at $0.10 is a valid resting order despite a notional under $1.
        stubSpec(UNIT / 100, 0, 5 * UNIT);
        submitIntent(Statics.PRICE_SCALING_FACTOR / 10, 5 * UNIT);
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testNewOrderBelowMinSizeIsRejected() {
        stubSpec(UNIT / 100, 0, 5 * UNIT);
        submitIntent(Statics.PRICE_SCALING_FACTOR / 2, 4 * UNIT + 99 * (UNIT / 100));
        assertEquals(0, delegate.newOrders.size());
    }

    @Test
    void testNewOrderWithMinSizeZeroIsNotConstrained() {
        stubSpec(0, 0, 0);
        submitIntent(100L, 1L);
        assertEquals(1, delegate.newOrders.size());
    }

    @Test
    void testModifyBelowMinSizeIsRejected() {
        stubSpec(0, 0, 5 * UNIT);
        submitIntent(100L, 10L * UNIT);
        assertEquals(1, delegate.newOrders.size());

        ackOrder(delegate.newOrders.get(0));

        submitIntent(100L, 4L * UNIT);
        assertEquals(0, delegate.modifies.size());
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
    void testNewOrderIsRejectedWhenEveryOrderSlotIsOpen() {
        OrderManagementSystem fullOms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(1),
                new DefaultPositionTracker(new SharedPositionBuffer(8)),
                new RiskEngine(),
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                LedgerSink.NONE,
                () -> 5_000L);
        stubSpec(0, 0);

        Intent bidOnly = new Intent();
        bidOnly.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(100L)
                .bidSize(UNIT)
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue());
        fullOms.processIntent(bidOnly, delegate);
        assertEquals(1, delegate.newOrders.size());

        Intent addAsk = new Intent();
        addAsk.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(100L)
                .bidSize(UNIT)
                .askPrice(110L)
                .askSize(UNIT);
        delegate.clear();
        fullOms.processIntent(addAsk, delegate);

        assertEquals(0, delegate.newOrders.size());
        assertEquals(List.of(RejectReason.RISK_LIMIT_EXCEEDED), delegate.rejects);
        // The OMS stamps its own rejects with its clock rather than leaving the venue-time fields empty.
        assertEquals(List.of(5_000L), delegate.rejectTimestamps);
    }

    @Test
    void testARefusedOrderIsCountedInTheLedgerWithoutAnOrderRecord() {
        LedgerRing ledger =
                new LedgerRing(16, 4, Long.MAX_VALUE, new DefaultPositionTracker(new SharedPositionBuffer(8)));
        OrderManagementSystem fullOms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(1),
                new DefaultPositionTracker(new SharedPositionBuffer(8)),
                new RiskEngine(),
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                ledger,
                () -> 5_000L);
        stubSpec(0, 0);
        Intent both = new Intent();
        both.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(100L)
                .bidSize(UNIT)
                .askPrice(110L)
                .askSize(UNIT);

        fullOms.processIntent(both, delegate);
        ledger.isFailing(5_000L);

        List<LedgerEvent> events = new ArrayList<>();
        ledger.read(
                event -> {
                    LedgerEvent copy = new LedgerEvent();
                    copy.copyFrom(event);
                    events.add(copy);
                },
                16);
        assertEquals(
                List.of(LedgerEventType.ORDER_OPENED, LedgerEventType.REJECT_COUNT),
                events.stream().map(event -> event.type).toList(),
                "the accepted bid is recorded; the refused ask is only counted");
        assertEquals(LISTING_ID, events.get(1).listingId);
        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, events.get(1).rejectReason);
        assertEquals(1, events.get(1).count);
    }

    @Test
    void testCancelRejectForUnknownOrderIsDiscardedSilently() {
        Logger mockLogger = mock(Logger.class);
        OrderManagementSystem testOms = new OrderManagementSystem(
                mockLogger,
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(8)),
                new RiskEngine(),
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                LedgerSink.NONE,
                () -> 0L);

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
        stubSpec(lotSize, minNotional, 0);
    }

    private void stubSpec(long lotSize, long minNotional, long minSize) {
        when(securityMaster.getListingSpec(LISTING_ID))
                .thenReturn(new ListingSpec(LISTING_ID, 1, lotSize, minNotional, 1, minSize));
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

    private void submitMarketIntent(Side side, long size) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(IntentDecoder.bidPriceNullValue())
                .bidSize(IntentDecoder.bidSizeNullValue())
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue())
                .takeSize(size)
                .takeSide(side)
                .takeOrderType(OrderType.MARKET)
                .takeLimitPrice(IntentDecoder.takeLimitPriceNullValue());
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
        final List<RejectReason> rejects = new ArrayList<>();
        final List<Long> rejectTimestamps = new ArrayList<>();

        void clear() {
            newOrders.clear();
            modifies.clear();
            cancels.clear();
            rejects.clear();
            rejectTimestamps.clear();
        }

        @Override
        public void onExecReport(OrderExecutionReport report) {
            if (report.decoder.execType() == ExecType.REJECT) {
                rejects.add(report.decoder.rejectReason());
                rejectTimestamps.add(report.decoder.timestampRecv());
            }
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
