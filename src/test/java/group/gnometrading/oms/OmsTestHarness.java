package group.gnometrading.oms;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.RingBufferOrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable test harness that wires a complete OMS with real components.
 * Uses a mock SecurityMaster for listing lookups.
 */
public final class OmsTestHarness {

    static final int EXCHANGE_ID = 1;
    static final int SECURITY_ID = 42;
    static final int LISTING_ID = 100;
    static final int STRATEGY_ID = 7;

    final OrderManagementSystem oms;
    final RecordingSink sink;
    final SecurityMaster securityMaster;
    final DefaultPositionTracker positionTracker;
    final RingBufferOrderStateManager orderStateManager;
    final RiskEngine riskEngine;

    OmsTestHarness() {
        this(new RiskEngine());
    }

    OmsTestHarness(RiskEngine riskEngine) {
        this.riskEngine = riskEngine;
        this.securityMaster = mock(SecurityMaster.class);
        this.orderStateManager = new RingBufferOrderStateManager(64);
        this.positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(16));
        this.oms = new OrderManagementSystem(
                new NullLogger(),
                orderStateManager,
                positionTracker,
                riskEngine,
                securityMaster,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1));
        this.sink = new RecordingSink();
        stubDefaultListing();
    }

    void stubDefaultListing() {
        stubListing(EXCHANGE_ID, SECURITY_ID, LISTING_ID, 0, 0);
    }

    void stubListing(int exchangeId, int securityId, int listingId, long lotSize, long minNotional) {
        Listing listing = new Listing(
                listingId,
                new Exchange(exchangeId, "TEST", "US", null),
                new Security(securityId, "SYM", 1),
                "SYM",
                "SYM");
        when(securityMaster.getListing(exchangeId, securityId)).thenReturn(listing);
        when(securityMaster.getListingSpec(listingId)).thenReturn(new ListingSpec(listingId, 1, lotSize, minNotional));
    }

    void stubListingSpec(int listingId, long lotSize, long minNotional) {
        when(securityMaster.getListingSpec(listingId)).thenReturn(new ListingSpec(listingId, 1, lotSize, minNotional));
    }

    long submitBidIntent(long price, long size) {
        return submitBidIntent(STRATEGY_ID, SECURITY_ID, price, size);
    }

    long submitAskIntent(long price, long size) {
        return submitAskIntent(STRATEGY_ID, SECURITY_ID, price, size);
    }

    long submitBidIntent(int strategyId, long securityId, long price, long size) {
        Intent intent =
                buildIntent(strategyId, EXCHANGE_ID, securityId, price, size, IntentDecoder.askPriceNullValue(), 0);
        oms.processIntent(intent, sink);
        return sink.lastNewOrderCounter();
    }

    long submitAskIntent(int strategyId, long securityId, long price, long size) {
        Intent intent =
                buildIntent(strategyId, EXCHANGE_ID, securityId, IntentDecoder.bidPriceNullValue(), 0, price, size);
        oms.processIntent(intent, sink);
        return sink.lastNewOrderCounter();
    }

    void submitBothIntent(long bidPrice, long bidSize, long askPrice, long askSize) {
        Intent intent = buildIntent(STRATEGY_ID, EXCHANGE_ID, SECURITY_ID, bidPrice, bidSize, askPrice, askSize);
        oms.processIntent(intent, sink);
    }

    void submitBothIntent(int strategyId, long securityId, long bidPrice, long bidSize, long askPrice, long askSize) {
        Intent intent = buildIntent(strategyId, EXCHANGE_ID, securityId, bidPrice, bidSize, askPrice, askSize);
        oms.processIntent(intent, sink);
    }

    long submitTakeIntent(long takeSize, Side takeSide) {
        return submitTakeIntent(STRATEGY_ID, SECURITY_ID, takeSize, takeSide);
    }

    long submitTakeIntent(int strategyId, long securityId, long takeSize, Side takeSide) {
        Intent intent = buildTakeIntent(
                strategyId,
                EXCHANGE_ID,
                securityId,
                takeSize,
                takeSide,
                OrderType.NULL_VAL,
                IntentDecoder.takeLimitPriceNullValue());
        oms.processIntent(intent, sink);
        return sink.lastNewOrderCounter();
    }

    void submitCancelIntent() {
        Intent intent = buildIntent(
                STRATEGY_ID,
                EXCHANGE_ID,
                SECURITY_ID,
                IntentDecoder.bidPriceNullValue(),
                0,
                IntentDecoder.askPriceNullValue(),
                0);
        oms.processIntent(intent, sink);
    }

    void injectAck(long clientOidCounter) {
        injectExecReport(clientOidCounter, ExecType.NEW, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectAck(long clientOidCounter, long leavesQty) {
        injectExecReport(
                clientOidCounter, ExecType.NEW, 0, 0, 0, leavesQty, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectFill(long clientOidCounter, long filledQty, long fillPrice, long cumQty, long leavesQty) {
        injectFill(clientOidCounter, filledQty, fillPrice, cumQty, leavesQty, 0);
    }

    void injectFill(long clientOidCounter, long filledQty, long fillPrice, long cumQty, long leavesQty, long fee) {
        ExecType type = leavesQty == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
        injectExecReport(clientOidCounter, type, filledQty, fillPrice, cumQty, leavesQty, fee);
    }

    void injectCancel(long clientOidCounter) {
        injectExecReport(clientOidCounter, ExecType.CANCEL, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectReject(long clientOidCounter) {
        injectExecReport(clientOidCounter, ExecType.REJECT, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectCancelReject(long clientOidCounter) {
        injectExecReport(
                clientOidCounter, ExecType.CANCEL_REJECT, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectExpire(long clientOidCounter) {
        injectExecReport(clientOidCounter, ExecType.EXPIRE, 0, 0, 0, 0, OrderExecutionReportDecoder.feeNullValue());
    }

    void injectExecReport(
            long clientOidCounter,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumQty,
            long leavesQty,
            long fee) {
        injectExecReport(STRATEGY_ID, clientOidCounter, execType, filledQty, fillPrice, cumQty, leavesQty, fee);
    }

    void injectExecReport(
            int strategyId,
            long clientOidCounter,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumQty,
            long leavesQty,
            long fee) {
        injectExecReport(
                strategyId,
                clientOidCounter,
                EXCHANGE_ID,
                SECURITY_ID,
                execType,
                filledQty,
                fillPrice,
                cumQty,
                leavesQty,
                fee);
    }

    void injectExecReport(
            int strategyId,
            long clientOidCounter,
            int exchangeId,
            int securityId,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumQty,
            long leavesQty,
            long fee) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, strategyId);
        report.encoder
                .exchangeId(exchangeId)
                .securityId(securityId)
                .orderId(0)
                .execType(execType)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty((int) filledQty)
                .fillPrice(fillPrice)
                .cumulativeQty((int) cumQty)
                .leavesQty((int) leavesQty)
                .timestampEvent(0)
                .timestampRecv(0)
                .fee(fee);
        report.encoder.flags().clear();
        oms.processExecutionReport(report, sink);
    }

    void injectAck(int strategyId, long clientOidCounter, int exchangeId, int securityId, long leavesQty) {
        injectExecReport(
                strategyId,
                clientOidCounter,
                exchangeId,
                securityId,
                ExecType.NEW,
                0,
                0,
                0,
                leavesQty,
                OrderExecutionReportDecoder.feeNullValue());
    }

    void injectFill(
            int strategyId,
            long clientOidCounter,
            int exchangeId,
            int securityId,
            long filledQty,
            long fillPrice,
            long cumQty,
            long leavesQty,
            long fee) {
        ExecType type = leavesQty == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
        injectExecReport(
                strategyId,
                clientOidCounter,
                exchangeId,
                securityId,
                type,
                filledQty,
                fillPrice,
                cumQty,
                leavesQty,
                fee);
    }

    void injectCancel(int strategyId, long clientOidCounter, int exchangeId, int securityId) {
        injectExecReport(
                strategyId,
                clientOidCounter,
                exchangeId,
                securityId,
                ExecType.CANCEL,
                0,
                0,
                0,
                0,
                OrderExecutionReportDecoder.feeNullValue());
    }

    void injectReject(int strategyId, long clientOidCounter, int exchangeId, int securityId) {
        injectExecReport(
                strategyId,
                clientOidCounter,
                exchangeId,
                securityId,
                ExecType.REJECT,
                0,
                0,
                0,
                0,
                OrderExecutionReportDecoder.feeNullValue());
    }

    Position getPosition(int listingId) {
        return oms.getPosition(listingId);
    }

    Position getStrategyPosition(int strategyId, int listingId) {
        return oms.getStrategyPosition(strategyId, listingId);
    }

    TrackedOrder getTrackedOrder(long clientOidCounter) {
        return oms.getOrder(clientOidCounter);
    }

    static Intent buildIntent(
            int strategyId, int exchangeId, long securityId, long bidPrice, long bidSize, long askPrice, long askSize) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(strategyId)
                .exchangeId(exchangeId)
                .securityId((int) securityId)
                .bidPrice(bidPrice)
                .bidSize(bidSize == 0 ? IntentDecoder.bidSizeNullValue() : bidSize)
                .askPrice(askPrice)
                .askSize(askSize == 0 ? IntentDecoder.askSizeNullValue() : askSize)
                .takeSize(IntentDecoder.takeSizeNullValue());
        return intent;
    }

    static Intent buildTakeIntent(
            int strategyId,
            int exchangeId,
            long securityId,
            long takeSize,
            Side takeSide,
            OrderType takeOrderType,
            long takeLimitPrice) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(strategyId)
                .exchangeId(exchangeId)
                .securityId((int) securityId)
                .bidPrice(IntentDecoder.bidPriceNullValue())
                .bidSize(IntentDecoder.bidSizeNullValue())
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue())
                .takeSize(takeSize)
                .takeSide(takeSide)
                .takeOrderType(takeOrderType)
                .takeLimitPrice(takeLimitPrice);
        return intent;
    }

    static OrderExecutionReport buildExecReport(
            int strategyId,
            long clientOidCounter,
            int exchangeId,
            int securityId,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumQty,
            long leavesQty,
            long fee) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, strategyId);
        report.encoder
                .exchangeId(exchangeId)
                .securityId(securityId)
                .orderId(0)
                .execType(execType)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty((int) filledQty)
                .fillPrice(fillPrice)
                .cumulativeQty((int) cumQty)
                .leavesQty((int) leavesQty)
                .timestampEvent(0)
                .timestampRecv(0)
                .fee(fee);
        report.encoder.flags().clear();
        return report;
    }

    public static Order buildOrder(
            int strategyId, long clientOidCounter, int exchangeId, int securityId, Side side, long price, long size) {
        Order order = new Order();
        order.encodeClientOid(clientOidCounter, strategyId);
        order.encoder
                .exchangeId((short) exchangeId)
                .securityId(securityId)
                .price(price)
                .size((int) size)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encoder.flags().clear();
        return order;
    }

    static final class RecordingSink implements ActionSink {
        final List<NewOrderCapture> newOrders = new ArrayList<>();
        final List<ModifyCapture> modifies = new ArrayList<>();
        final List<Long> cancels = new ArrayList<>();
        final List<ExecReportCapture> execReports = new ArrayList<>();

        void clear() {
            newOrders.clear();
            modifies.clear();
            cancels.clear();
            execReports.clear();
        }

        long lastNewOrderCounter() {
            return newOrders.isEmpty() ? -1 : newOrders.get(newOrders.size() - 1).clientOidCounter;
        }

        @Override
        public void onNewOrder(Order order) {
            newOrders.add(new NewOrderCapture(
                    order.getClientOidCounter(),
                    order.decoder.price(),
                    order.decoder.size(),
                    order.decoder.side(),
                    order.decoder.orderType(),
                    order.decoder.timeInForce()));
        }

        @Override
        public void onModify(ModifyOrder modify) {
            modifies.add(
                    new ModifyCapture(modify.getClientOidCounter(), modify.decoder.price(), modify.decoder.size()));
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            cancels.add(cancel.getClientOidCounter());
        }

        @Override
        public void onExecReport(OrderExecutionReport report) {
            execReports.add(new ExecReportCapture(
                    report.getClientOidCounter(),
                    report.decoder.execType(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    report.decoder.cumulativeQty(),
                    report.decoder.leavesQty(),
                    report.decoder.fee(),
                    report.decoder.rejectReason()));
        }
    }

    record NewOrderCapture(
            long clientOidCounter, long price, long size, Side side, OrderType orderType, TimeInForce timeInForce) {}

    record ModifyCapture(long clientOidCounter, long price, long size) {}

    record ExecReportCapture(
            long clientOidCounter,
            ExecType execType,
            long filledQty,
            long fillPrice,
            long cumulativeQty,
            long leavesQty,
            long fee,
            RejectReason rejectReason) {}
}
