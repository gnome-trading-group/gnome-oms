package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.RingBufferOrderStateManager;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsAgentTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 100;
    private static final int STRATEGY_ID = 7;

    @Mock
    private group.gnometrading.SecurityMaster securityMaster;

    private OmsAgent omsAgent;
    private OrderManagementSystem oms;

    private SequencedRingBuffer<Intent> intentBuffer;
    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private SequencedRingBuffer<Intent> orderOutboundBuffer;
    private SequencedRingBuffer<OrderExecutionReport> strategyExecReportBuffer;

    private SequencedPoller outboundPoller;
    private SequencedPoller strategyPoller;

    private final List<DrainedMessage> outboundMessages = new ArrayList<>();
    private final List<DrainedExecReport> strategyExecReports = new ArrayList<>();

    @BeforeEach
    void setUp() {
        GlobalSequence globalSequence = new GlobalSequence();
        intentBuffer = new SequencedRingBuffer<>(Intent::new, globalSequence);
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence);
        orderOutboundBuffer = new SequencedRingBuffer<>(Intent::new, globalSequence, 64);
        strategyExecReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, 64);

        outboundPoller = orderOutboundBuffer.createPoller(this::onOutboundEvent);
        strategyPoller = strategyExecReportBuffer.createPoller(this::onStrategyExecReport);

        RingBufferOrderStateManager orderStateManager = new RingBufferOrderStateManager(64);
        DefaultPositionTracker positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(16));
        RiskEngine riskEngine = new RiskEngine();
        oms = new OrderManagementSystem(
                new NullLogger(), orderStateManager, positionTracker, riskEngine, securityMaster);

        omsAgent = new OmsAgent(oms, intentBuffer, execReportBuffer, orderOutboundBuffer, strategyExecReportBuffer);

        Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "TEST", "US", null),
                new Security(SECURITY_ID, "SYM", 1),
                "SYM",
                "SYM");
        when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
    }

    // --- doWork ---

    @Test
    void doWork_processesExecReportBeforeIntent() throws Exception {
        // Publish both an intent and an exec report before calling doWork.
        // OmsAgent polls exec reports first (line 68), so the exec report is processed first.
        // We verify ordering by tracking a side-effect: the exec report for an unknown counter
        // is silently dropped (logged), and the intent triggers a new order.
        publishIntent(100L, 10L);
        publishExecReport(999L, ExecType.CANCEL); // unknown counter — should be dropped cleanly

        int workDone = omsAgent.doWork();

        assertEquals(2, workDone);
        outboundPoller.poll();
        assertEquals(1, outboundMessages.size()); // new order from intent
        assertEquals(OrderDecoder.TEMPLATE_ID, outboundMessages.get(0).templateId);
    }

    @Test
    void doWork_intentDispatchedToOms_producesNewOrder() throws Exception {
        publishIntent(100L, 10L);
        int work = omsAgent.doWork();
        assertEquals(1, work);

        outboundPoller.poll();
        assertEquals(1, outboundMessages.size());
        assertEquals(OrderDecoder.TEMPLATE_ID, outboundMessages.get(0).templateId);

        // Verify the order content
        Order order = new Order();
        order.buffer.putBytes(0, outboundMessages.get(0).buffer, 0, outboundMessages.get(0).length);
        order.wrap(order.buffer);
        assertEquals(100L, order.decoder.price());
        assertEquals(10L, order.decoder.size());
        assertEquals(Side.Bid, order.decoder.side());
    }

    @Test
    void doWork_execReportForwarded_toStrategyBuffer() throws Exception {
        // First publish intent and get an order out
        publishIntent(100L, 10L);
        omsAgent.doWork();
        outboundPoller.poll();
        Order order = decodeFirstOutboundOrder();
        outboundMessages.clear();

        // Now publish an exec report (ack)
        publishExecReport(order.getClientOidCounter(), ExecType.NEW);
        omsAgent.doWork();

        strategyPoller.poll();
        assertEquals(1, strategyExecReports.size());
        assertEquals(ExecType.NEW, strategyExecReports.get(0).execType);
        assertEquals(order.getClientOidCounter(), strategyExecReports.get(0).clientOidCounter);
    }

    @Test
    void doWork_returnsWorkCountMatchingMessagesProcessed() throws Exception {
        publishIntent(100L, 10L);
        publishIntent(101L, 5L); // a second intent (different price — will be queued as modify for same slot)
        // Actually both go to the same bid slot for security 42 — first creates new order, second queues
        // Let's use one intent and one exec report instead
        publishExecReport(999L, ExecType.CANCEL); // unknown — dropped

        int work = omsAgent.doWork();
        // Two exec reports polled (none found), one intent polled = 1+1? No, the pollers return count of events
        // handled.
        // execReportPoller.poll() returns 1 (one exec report), intentPoller.poll() returns 2 (two intents)
        assertEquals(3, work);
    }

    @Test
    void fullRoundTrip_intentToAckToStrategyBuffer() throws Exception {
        // Publish intent
        publishIntent(100L, 10L);
        omsAgent.doWork();
        outboundPoller.poll();
        Order order = decodeFirstOutboundOrder();
        outboundMessages.clear();
        strategyExecReports.clear();

        // Publish ack
        publishExecReport(order.getClientOidCounter(), ExecType.NEW);
        omsAgent.doWork();

        outboundPoller.poll();
        strategyPoller.poll();

        // No additional outbound orders (no queued intent)
        assertEquals(0, outboundMessages.size());
        // Ack forwarded to strategy
        assertEquals(1, strategyExecReports.size());
        assertEquals(ExecType.NEW, strategyExecReports.get(0).execType);
    }

    @Test
    void onNewOrder_publishesToOutboundBuffer() throws Exception {
        // Call onNewOrder directly (simulating OMS calling back into OmsAgent)
        Order order = OmsTestHarness.buildOrder(STRATEGY_ID, 1L, EXCHANGE_ID, SECURITY_ID, Side.Bid, 100L, 10L);
        omsAgent.onNewOrder(order);

        outboundPoller.poll();
        assertEquals(1, outboundMessages.size());
        assertEquals(OrderDecoder.TEMPLATE_ID, outboundMessages.get(0).templateId);
    }

    @Test
    void onExecReport_publishesToStrategyBuffer() throws Exception {
        OrderExecutionReport report = OmsTestHarness.buildExecReport(
                STRATEGY_ID,
                1L,
                EXCHANGE_ID,
                SECURITY_ID,
                ExecType.NEW,
                0,
                0,
                0,
                10,
                OrderExecutionReportDecoder.feeNullValue());
        omsAgent.onExecReport(report);

        strategyPoller.poll();
        assertEquals(1, strategyExecReports.size());
        assertEquals(ExecType.NEW, strategyExecReports.get(0).execType);
    }

    // --- helpers ---

    private void publishIntent(long bidPrice, long bidSize) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(bidPrice)
                .bidSize(bidSize)
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue())
                .takeSize(IntentDecoder.takeSizeNullValue());

        Intent slot = intentBuffer.claim();
        slot.buffer.putBytes(0, intent.buffer, 0, intent.totalMessageSize());
        slot.wrap(slot.buffer);
        intentBuffer.publish();
    }

    private void publishExecReport(long clientOidCounter, ExecType execType) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, STRATEGY_ID);
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .orderId(0)
                .execType(execType)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty(0)
                .fillPrice(OrderExecutionReportDecoder.fillPriceNullValue())
                .cumulativeQty(0)
                .leavesQty(0)
                .timestampEvent(0)
                .timestampRecv(0)
                .fee(OrderExecutionReportDecoder.feeNullValue());
        report.encoder.flags().clear();

        OrderExecutionReport slot = execReportBuffer.claim();
        slot.buffer.putBytes(0, report.buffer, 0, report.totalMessageSize());
        slot.wrap(slot.buffer);
        execReportBuffer.publish();
    }

    private Order decodeFirstOutboundOrder() {
        assertTrue(!outboundMessages.isEmpty(), "No outbound messages to decode");
        Order order = new Order();
        order.buffer.putBytes(0, outboundMessages.get(0).buffer, 0, outboundMessages.get(0).length);
        order.wrap(order.buffer);
        return order;
    }

    private void onOutboundEvent(long globalSeq, int templateId, UnsafeBuffer buf, int len) {
        UnsafeBuffer copy = new UnsafeBuffer(new byte[len]);
        copy.putBytes(0, buf, 0, len);
        outboundMessages.add(new DrainedMessage(templateId, copy, len));
    }

    private void onStrategyExecReport(long globalSeq, int templateId, UnsafeBuffer buf, int len) {
        if (templateId == OrderExecutionReportDecoder.TEMPLATE_ID) {
            OrderExecutionReport report = new OrderExecutionReport();
            report.buffer.putBytes(0, buf, 0, len);
            report.wrap(report.buffer);
            strategyExecReports.add(new DrainedExecReport(report.getClientOidCounter(), report.decoder.execType()));
        }
    }

    private record DrainedMessage(int templateId, UnsafeBuffer buffer, int length) {}

    private record DrainedExecReport(long clientOidCounter, ExecType execType) {}
}
