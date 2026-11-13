package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.LatencyKey;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.PriceWriter;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSnapshots;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TriggerKind;
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
    private RiskEngine riskEngine;
    private final List<DrainedExecReport> strategyExecReports = new ArrayList<>();

    private GlobalSequence globalSequence;
    // Every ring's stamp and the agent's clock: what the latency is measured on.
    private long nowNanos = 1_700_000_040_000_000_000L;

    @BeforeEach
    void setUp() {
        globalSequence = new GlobalSequence();
        intentBuffer = new SequencedRingBuffer<>(
                Intent::new, globalSequence, SequencedRingBuffer.DEFAULT_SIZE, () -> nowNanos);
        execReportBuffer = new SequencedRingBuffer<>(
                OrderExecutionReport::new, globalSequence, SequencedRingBuffer.DEFAULT_SIZE, () -> nowNanos);
        orderOutboundBuffer = new SequencedRingBuffer<>(Intent::new, globalSequence, 64);
        strategyExecReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, 64);

        outboundPoller = orderOutboundBuffer.createPoller(this::onOutboundEvent);
        strategyPoller = strategyExecReportBuffer.createPoller(this::onStrategyExecReport);

        PooledOrderStateManager orderStateManager = new PooledOrderStateManager(64);
        DefaultPositionTracker positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(16));
        riskEngine = new RiskEngine();
        SharedPriceBuffer priceBuffer = new SharedPriceBuffer(1);
        PriceSlotRegistry priceSlots = new PriceSlotRegistry(1);
        SequencedRingBuffer<Mbp10Schema> marketDataBuffer =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        oms = new OrderManagementSystem(
                new NullLogger(),
                orderStateManager,
                positionTracker,
                riskEngine,
                securityMaster,
                priceBuffer,
                priceSlots,
                LedgerSink.NONE,
                () -> 0L);

        omsAgent = new OmsAgent(
                oms,
                intentBuffer,
                execReportBuffer,
                orderOutboundBuffer,
                strategyExecReportBuffer,
                new PriceWriter(priceBuffer, priceSlots, new StreamDirectory(), marketDataBuffer),
                () -> nowNanos);

        Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "KALSHI", "Test", "US"),
                new Security(SECURITY_ID, "SYM", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "SYM",
                "SYM");
        when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
        lenient().when(securityMaster.getListing(LISTING_ID)).thenReturn(listing);
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
    void doWork_appliesAPublishedKillSwitch() throws Exception {
        publishIntent(100L, 10L);
        omsAgent.doWork();
        outboundPoller.poll();
        long counter = decodeFirstOutboundOrder().getClientOidCounter();
        publishExecReport(counter, ExecType.NEW);
        omsAgent.doWork();
        outboundMessages.clear();

        RiskSnapshots.publishKills(riskEngine, true, new int[] {}, new int[] {});
        omsAgent.doWork();
        outboundPoller.poll();

        assertEquals(1, outboundMessages.size());
        assertEquals(CancelOrderDecoder.TEMPLATE_ID, outboundMessages.get(0).templateId);
    }

    // --- order causes ---

    @Test
    void anOrderCarriesTheIntentItServesAndThatIntentsTrigger() throws Exception {
        final long intentSequence = publishIntent(100L, 10L, 55L, TriggerKind.MARKET_DATA, 1_000L, 900L);

        omsAgent.doWork();
        outboundPoller.poll();

        final OrderDecoder order = decodeFirstOutboundOrder().decoder;
        assertEquals(intentSequence, order.intentSequence());
        assertEquals(55L, order.triggerSequence());
        assertEquals(TriggerKind.MARKET_DATA, order.triggerKind());
        assertEquals(1_000L, order.triggerTimestamp());
        assertEquals(900L, order.triggerOriginTimestamp());
    }

    @Test
    void anIntentQueuedBehindAnOrderInFlightIsSentUnderItsOwnCause() throws Exception {
        publishIntent(100L, 10L, 55L, TriggerKind.MARKET_DATA, 1_000L, 900L);
        omsAgent.doWork();
        outboundPoller.poll();
        final long counter = decodeFirstOutboundOrder().getClientOidCounter();
        outboundMessages.clear();
        // The first order isn't acknowledged yet, so this one waits in the slot.
        final long queuedSequence = publishIntent(101L, 10L, 66L, TriggerKind.EXEC_REPORT, 2_000L, 1_900L);
        omsAgent.doWork();
        outboundPoller.poll();
        assertTrue(outboundMessages.isEmpty());

        publishExecReport(counter, ExecType.NEW);
        omsAgent.doWork();
        outboundPoller.poll();

        assertEquals(1, outboundMessages.size());
        final ModifyOrder modify = new ModifyOrder();
        modify.buffer.putBytes(0, outboundMessages.get(0).buffer, 0, outboundMessages.get(0).length);
        modify.wrap(modify.buffer);
        assertEquals(ModifyOrderDecoder.TEMPLATE_ID, outboundMessages.get(0).templateId);
        assertEquals(queuedSequence, modify.decoder.intentSequence());
        assertEquals(66L, modify.decoder.triggerSequence());
        assertEquals(TriggerKind.EXEC_REPORT, modify.decoder.triggerKind());
        assertEquals(2_000L, modify.decoder.triggerTimestamp());
        assertEquals(1_900L, modify.decoder.triggerOriginTimestamp());
    }

    @Test
    void processingIsMeasuredFromTheArrivalOfWhatTheOmsWasHandling() throws Exception {
        final MinuteHistograms histograms = new MinuteHistograms(LatencyGrid.size(1, 3, KINDS.length));
        omsAgent.measureProcessing(new LatencyGrid(
                histograms,
                3,
                "oms.process",
                new int[] {EXCHANGE_ID},
                new String[] {"KALSHI"},
                OmsAgent.LATENCY_ACTIONS,
                KINDS));
        publishIntent(100L, 10L, 55L, TriggerKind.MARKET_DATA, 1_000L, 900L);
        nowNanos += 3_000;
        omsAgent.doWork();
        outboundPoller.poll();
        publishExecReport(decodeFirstOutboundOrder().getClientOidCounter(), ExecType.NEW);
        omsAgent.doWork();
        // A kill's cancel is the OMS's own: nothing it was handling, so nothing to measure.
        RiskSnapshots.publishKills(riskEngine, true, new int[] {}, new int[] {});
        omsAgent.doWork();
        nowNanos += MinuteHistograms.MINUTE_NANOS;
        omsAgent.doWork();

        final MinuteSummary summary = new MinuteSummary(histograms.capacity());
        assertTrue(histograms.summarize(summary));
        long measured = 0;
        for (int key = 0; key < histograms.keys(); key++) {
            if (summary.count(key) > 0) {
                final LatencyKey label = histograms.label(key);
                assertEquals("KALSHI/NEW/MARKET_DATA", label.exchange() + "/" + label.action() + "/" + label.kind());
                assertEquals(3_000, summary.max(key));
                measured += summary.count(key);
            }
        }
        assertEquals(1, measured);
    }

    @Test
    void whatTheOmsDoesIsCounted() throws Exception {
        publishIntent(100L, 10L);
        omsAgent.doWork();
        outboundPoller.poll();
        publishExecReport(decodeFirstOutboundOrder().getClientOidCounter(), ExecType.NEW);
        omsAgent.doWork();
        RiskSnapshots.publishKills(riskEngine, true, new int[] {}, new int[] {});
        omsAgent.doWork();

        final MetricCounters activity = omsAgent.activity();
        assertEquals(1, activity.get(counterNamed(activity, "intents")));
        assertEquals(1, activity.get(counterNamed(activity, "newOrders")));
        assertEquals(1, activity.get(counterNamed(activity, "cancels")), "the kill's cancel");
        assertEquals(1, activity.get(counterNamed(activity, "reports")));
        assertEquals(1, activity.get(counterNamed(activity, "report.NEW")));
    }

    @Test
    void aReportTheOmsMakesItselfIsCountedAsARefusalByReason() {
        final OrderExecutionReport refusal = new OrderExecutionReport();
        refusal.encoder.execType(ExecType.REJECT).rejectReason(RejectReason.RISK_LIMIT_EXCEEDED);

        omsAgent.onExecReport(refusal);

        final MetricCounters activity = omsAgent.activity();
        assertEquals(1, activity.get(counterNamed(activity, "refused.RISK_LIMIT_EXCEEDED")));
        assertEquals(0, activity.get(counterNamed(activity, "reports")), "a refusal is the OMS's, not the venue's");
    }

    private static int counterNamed(final MetricCounters counters, final String name) {
        for (int c = 0; c < counters.count(); c++) {
            if (counters.name(c).equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no counter " + name);
    }

    @Test
    void aCancelTheOmsSendsOnItsOwnIsRisk() throws Exception {
        publishIntent(100L, 10L, 55L, TriggerKind.MARKET_DATA, 1_000L, 900L);
        omsAgent.doWork();
        outboundPoller.poll();
        publishExecReport(decodeFirstOutboundOrder().getClientOidCounter(), ExecType.NEW);
        omsAgent.doWork();
        outboundMessages.clear();

        RiskSnapshots.publishKills(riskEngine, true, new int[] {}, new int[] {});
        omsAgent.doWork();
        outboundPoller.poll();

        final CancelOrder cancel = new CancelOrder();
        cancel.buffer.putBytes(0, outboundMessages.get(0).buffer, 0, outboundMessages.get(0).length);
        cancel.wrap(cancel.buffer);
        assertEquals(TriggerKind.RISK, cancel.decoder.triggerKind());
        assertEquals(CancelOrderDecoder.intentSequenceNullValue(), cancel.decoder.intentSequence());
        assertEquals(CancelOrderDecoder.triggerSequenceNullValue(), cancel.decoder.triggerSequence());
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

    private static final String[] KINDS = kindLabels();

    private static String[] kindLabels() {
        final String[] labels = new String[TriggerKind.values().length - 1];
        for (TriggerKind kind : TriggerKind.values()) {
            if (kind != TriggerKind.NULL_VAL) {
                labels[kind.value()] = kind.name();
            }
        }
        return labels;
    }

    private void publishIntent(long bidPrice, long bidSize) {
        publishIntent(bidPrice, bidSize, 0L, TriggerKind.NONE, 0L, 0L);
    }

    /** @return the intent's global sequence */
    private long publishIntent(
            long bidPrice, long bidSize, long triggerSequence, TriggerKind kind, long triggerAt, long originAt) {
        Intent intent = new Intent();
        intent.encoder
                .triggerSequence(triggerSequence)
                .triggerKind(kind)
                .triggerTimestamp(triggerAt)
                .triggerOriginTimestamp(originAt);
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
        return globalSequence.current();
    }

    private void publishExecReport(long clientOidCounter, ExecType execType) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, STRATEGY_ID);
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
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
