package group.gnometrading.oms;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.oms.action.ActionSink;
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
 * <p>Risk policy sync is handled by a dedicated {@link group.gnometrading.oms.risk.RiskSyncAgent}
 * running on its own thread — this agent's hot path performs no I/O or sync work.
 *
 * <p>In production, run via {@link group.gnometrading.concurrent.GnomeAgentRunner}.
 * In backtest, call {@link #doWork()} manually at simulation step boundaries.
 */
public final class OmsAgent implements GnomeAgent, SequencedEventHandler, ActionSink {

    private final OrderManagementSystem oms;
    private final SequencedPoller intentPoller;
    private final SequencedPoller execReportPoller;
    private final SequencedRingBuffer<?> orderOutboundBuffer;
    private final SequencedRingBuffer<OrderExecutionReport> strategyExecReportBuffer;

    // Pre-allocated flyweights for decoding inbound events
    private final Intent intent = new Intent();
    private final OrderExecutionReport execReport = new OrderExecutionReport();

    public OmsAgent(
            final OrderManagementSystem oms,
            final SequencedRingBuffer<Intent> intentBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> strategyExecReportBuffer) {
        this.oms = oms;
        this.orderOutboundBuffer = orderOutboundBuffer;
        this.strategyExecReportBuffer = strategyExecReportBuffer;
        this.intentPoller = intentBuffer.createPoller(this);
        this.execReportPoller = execReportBuffer.createPoller(this);
    }

    @Override
    public void onStart() {
        // disambiguate: GnomeAgent.onStart() and Disruptor EventHandlerBase.onStart()
    }

    @Override
    public int doWork() throws Exception {
        int work = 0;
        work += execReportPoller.poll();
        work += intentPoller.poll();
        return work;
    }

    @Override
    public void onSequencedEvent(final long globalSeq, final int templateId, final UnsafeBuffer buf, final int len)
            throws Exception {
        if (templateId == IntentDecoder.TEMPLATE_ID) {
            intent.wrap(buf);
            oms.processIntent(intent, this);
        } else if (templateId == OrderExecutionReportDecoder.TEMPLATE_ID) {
            execReport.wrap(buf);
            oms.processExecutionReport(execReport, this);
            onExecReport(execReport);
        }
    }

    @Override
    public void onNewOrder(final Order order) {
        orderOutboundBuffer.publishRaw(order.buffer, order.messageHeaderDecoder.templateId(), order.totalMessageSize());
    }

    @Override
    public void onCancel(final CancelOrder cancel) {
        orderOutboundBuffer.publishRaw(
                cancel.buffer, cancel.messageHeaderDecoder.templateId(), cancel.totalMessageSize());
    }

    @Override
    public void onModify(final ModifyOrder modify) {
        orderOutboundBuffer.publishRaw(
                modify.buffer, modify.messageHeaderDecoder.templateId(), modify.totalMessageSize());
    }

    @Override
    public void onExecReport(final OrderExecutionReport report) {
        strategyExecReportBuffer.publishRaw(
                report.buffer, report.messageHeaderDecoder.templateId(), report.totalMessageSize());
    }
}
