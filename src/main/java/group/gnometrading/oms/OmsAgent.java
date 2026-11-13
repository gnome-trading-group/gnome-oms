package group.gnometrading.oms;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.pnl.PriceWriter;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The centralized OMS event loop.
 *
 * <p>Reads {@link Intent} messages from the intent ring buffer and
 * {@link OrderExecutionReport} messages from the exec report ring buffer, processes
 * them through the {@link OrderManagementSystem}, and writes approved
 * Order/CancelOrder/ModifyOrder actions to the order outbound ring buffer.
 *
 * <p>After processing each exec report from the gateway, the report (and any synthetic
 * risk-rejection reports) is forwarded to the strategy via the strategy exec report buffer.
 * This ensures the strategy only sees exec reports after the OMS has updated position state.
 *
 * <p>Every order, modify and cancel carries its {@link OrderCause}: the intent and trigger it serves, the execution
 * report it reacts to, or the OMS's own risk handling (anything outside an intent or report).
 *
 * <p>Given a {@link LatencyGrid} ({@link #measureProcessing}), it records {@code oms.process} for each: from the
 * arrival on its ring of the intent or report the OMS was handling to the order's publish, by exchange, action
 * ({@link #LATENCY_ACTIONS}) and trigger kind. Orders of the OMS's own (risk sweeps, shutdown) have no arrival and
 * aren't recorded.
 *
 * <p>Risk policy sync is handled by a dedicated {@link group.gnometrading.oms.risk.RiskSyncAgent}
 * running on its own thread — this agent's hot path performs no I/O or sync work.
 *
 * <p>In production, run via {@link group.gnometrading.concurrent.GnomeAgentRunner}.
 * In backtest, call {@link #doWork()} manually at simulation step boundaries.
 */
public final class OmsAgent implements GnomeAgent, SequencedEventHandler, ActionSink {

    /**
     * The action labels {@code oms.process} is recorded under, by index.
     */
    public static final String[] LATENCY_ACTIONS = {"NEW", "CANCEL", "MODIFY"};

    private static final int NEW = 0;
    private static final int CANCEL = 1;
    private static final int MODIFY = 2;

    private final OrderManagementSystem oms;
    private final SequencedPoller intentPoller;
    private final SequencedPoller execReportPoller;
    private final SequencedRingBuffer<?> orderOutboundBuffer;
    private final SequencedRingBuffer<OrderExecutionReport> strategyExecReportBuffer;
    private final EpochNanoClock nanoClock;
    private final PriceWriter priceWriter;
    private final OrderCause cause;
    private LatencyGrid processing;
    // When the intent or report being handled reached its ring; 0 outside one.
    private long handlingSince;

    // Pre-allocated flyweights for decoding inbound events
    private final Intent intent = new Intent();
    private final OrderExecutionReport execReport = new OrderExecutionReport();

    /**
     * {@code priceWriter} is polled on this agent's thread at the start of every pass, so marks stay current while no
     * intents arrive, and again before each intent. The strategy reads market data before it sends the intent that
     * reacts to it, so every book an intent was based on is in the price buffer before the OMS prices a market take or
     * checks for bad data. On a thread of its own the writer lagged by up to its idle sleep, and takes went out priced
     * off the previous book.
     */
    public OmsAgent(
            final OrderManagementSystem oms,
            final SequencedRingBuffer<Intent> intentBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> strategyExecReportBuffer,
            final PriceWriter priceWriter,
            final EpochNanoClock nanoClock) {
        this.priceWriter = priceWriter;
        this.oms = oms;
        this.orderOutboundBuffer = orderOutboundBuffer;
        this.strategyExecReportBuffer = strategyExecReportBuffer;
        this.nanoClock = nanoClock;
        this.cause = oms.orderCause();
        this.intentPoller = intentBuffer.createPoller(this);
        this.execReportPoller = execReportBuffer.createPoller(this);
    }

    /** Records {@code oms.process} into {@code grid}, which only this agent's thread may write; before it starts. */
    public void measureProcessing(final LatencyGrid grid) {
        this.processing = grid;
    }

    @Override
    public void onStart() {
        // disambiguate: GnomeAgent.onStart() and Disruptor EventHandlerBase.onStart()
    }

    @Override
    public int doWork() throws Exception {
        oms.applyRiskChanges(this);
        int work = priceWriter.poll();
        work += execReportPoller.poll();
        work += intentPoller.poll();
        // After order handling, so loss checks on a moved mark never delay an intent arriving with the tick.
        oms.checkMarkMoves(this);
        oms.checkLedger(this);
        if (processing != null) {
            processing.histograms().roll(nanoClock.nanoTime());
        }
        return work;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public void onSequencedEvent(final long globalSeq, final int templateId, final UnsafeBuffer buf, final int len)
            throws Exception {
        if (templateId == IntentDecoder.TEMPLATE_ID) {
            // Only here is the intent known to be visible, and with it every book the strategy read before sending it.
            // A poll between the two pollers leaves a gap: a book and intent published after it, but before the intent
            // poller reads what is available, reach the intent without the book.
            priceWriter.poll();
            intent.wrap(buf);
            cause.ofIntent(globalSeq, intent.decoder);
            handlingSince = intentPoller.timestampNanos();
            try {
                oms.processIntent(intent, this);
            } finally {
                cause.ofRisk();
                handlingSince = 0;
            }
        } else if (templateId == OrderExecutionReportDecoder.TEMPLATE_ID) {
            execReport.wrap(buf);
            handlingSince = execReportPoller.timestampNanos();
            cause.ofExecutionReport(globalSeq, handlingSince, execReport.decoder.timestampRecv());
            try {
                oms.processExecutionReport(execReport, this);
            } finally {
                cause.ofRisk();
                handlingSince = 0;
            }
            onExecReport(execReport);
        }
    }

    @Override
    public void onNewOrder(final Order order) {
        final long now = nanoClock.nanoTime();
        order.encoder.timestampSend(now);
        cause.stamp(order.encoder);
        orderOutboundBuffer.publishRaw(order.buffer, order.templateId(), order.totalMessageSize());
        recordProcessing(order.decoder.exchangeId(), NEW, now);
    }

    @Override
    public void onCancel(final CancelOrder cancel) {
        final long now = nanoClock.nanoTime();
        cancel.encoder.timestampSend(now);
        cause.stamp(cancel.encoder);
        orderOutboundBuffer.publishRaw(cancel.buffer, cancel.templateId(), cancel.totalMessageSize());
        recordProcessing(cancel.decoder.exchangeId(), CANCEL, now);
    }

    @Override
    public void onModify(final ModifyOrder modify) {
        final long now = nanoClock.nanoTime();
        modify.encoder.timestampSend(now);
        cause.stamp(modify.encoder);
        orderOutboundBuffer.publishRaw(modify.buffer, modify.templateId(), modify.totalMessageSize());
        recordProcessing(modify.decoder.exchangeId(), MODIFY, now);
    }

    private void recordProcessing(final int exchangeId, final int action, final long now) {
        if (processing != null && handlingSince != 0) {
            processing.record(exchangeId, action, cause.triggerKind().value(), now - handlingSince, now);
        }
    }

    @Override
    public void onExecReport(final OrderExecutionReport report) {
        strategyExecReportBuffer.publishRaw(report.buffer, report.templateId(), report.totalMessageSize());
    }

    @Override
    public void onClose() {
        oms.shutdownCancelAll(this);
    }
}
