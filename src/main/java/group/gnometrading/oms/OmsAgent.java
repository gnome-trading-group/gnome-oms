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
 * Order/CancelOrder/ModifyOrder actions to the outbound ring buffer.
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
    private final SequencedRingBuffer<?> outboundBuffer;

    // Pre-allocated flyweights for decoding inbound events
    private final Intent intent = new Intent();
    private final OrderExecutionReport execReport = new OrderExecutionReport();

    public OmsAgent(
            OrderManagementSystem oms,
            SequencedRingBuffer<Intent> intentBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            SequencedRingBuffer<?> outboundBuffer) {
        this.oms = oms;
        this.outboundBuffer = outboundBuffer;
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
    public void onSequencedEvent(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        if (templateId == IntentDecoder.TEMPLATE_ID) {
            intent.buffer.putBytes(0, buf, 0, len);
            oms.processIntent(intent, this);
        } else if (templateId == OrderExecutionReportDecoder.TEMPLATE_ID) {
            execReport.buffer.putBytes(0, buf, 0, len);
            oms.processExecutionReport(execReport, this);
        }
    }

    // --- ActionSink: write approved actions to the outbound ring buffer ---

    @Override
    public void onNewOrder(Order order) {
        outboundBuffer.publishRaw(order.buffer, order.messageHeaderDecoder.templateId(), order.totalMessageSize());
    }

    @Override
    public void onCancel(CancelOrder cancel) {
        outboundBuffer.publishRaw(cancel.buffer, cancel.messageHeaderDecoder.templateId(), cancel.totalMessageSize());
    }

    @Override
    public void onModify(ModifyOrder modify) {
        outboundBuffer.publishRaw(modify.buffer, modify.messageHeaderDecoder.templateId(), modify.totalMessageSize());
    }
}
