package group.gnometrading.oms;

import group.gnometrading.schemas.CancelOrderEncoder;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrderEncoder;
import group.gnometrading.schemas.OrderEncoder;
import group.gnometrading.schemas.TriggerKind;

/**
 * Why the OMS is sending what it sends: the intent it is acting on and that intent's trigger, the execution report
 * it is reacting to, or its own risk handling. {@link OmsAgent} sets it around each event and stamps it on every order,
 * modify and cancel it publishes, so each traces back to what caused it. An intent queued behind an order in flight
 * keeps its own cause, put back when it is finally sent. One per OMS, on the OMS thread; mutable, never allocated per
 * event.
 */
public final class OrderCause {

    private long intentSequence;
    private long triggerSequence;
    private TriggerKind triggerKind = TriggerKind.RISK;
    private long triggerTimestamp;
    private long triggerOriginTimestamp;

    /** Acting on the intent at {@code sequence}: its trigger is the one the strategy stamped on it. */
    public void ofIntent(final long sequence, final IntentDecoder intent) {
        this.intentSequence = sequence;
        this.triggerSequence = intent.triggerSequence();
        this.triggerKind = intent.triggerKind();
        this.triggerTimestamp = intent.triggerTimestamp();
        this.triggerOriginTimestamp = intent.triggerOriginTimestamp();
    }

    /** Reacting to the execution report at {@code sequence}, with no intent of its own behind it. */
    public void ofExecutionReport(final long sequence, final long arrivedAt, final long receivedAt) {
        this.intentSequence = 0;
        this.triggerSequence = sequence;
        this.triggerKind = TriggerKind.EXEC_REPORT;
        this.triggerTimestamp = arrivedAt;
        this.triggerOriginTimestamp = receivedAt;
    }

    /** The OMS acting on its own: a kill or halt sweep, a market-risk latch, shutdown. */
    public void ofRisk() {
        this.intentSequence = 0;
        this.triggerSequence = 0;
        this.triggerKind = TriggerKind.RISK;
        this.triggerTimestamp = 0;
        this.triggerOriginTimestamp = 0;
    }

    public void copyFrom(final OrderCause other) {
        this.intentSequence = other.intentSequence;
        this.triggerSequence = other.triggerSequence;
        this.triggerKind = other.triggerKind;
        this.triggerTimestamp = other.triggerTimestamp;
        this.triggerOriginTimestamp = other.triggerOriginTimestamp;
    }

    public void stamp(final OrderEncoder order) {
        order.intentSequence(intentSequence)
                .triggerSequence(triggerSequence)
                .triggerKind(triggerKind)
                .triggerTimestamp(triggerTimestamp)
                .triggerOriginTimestamp(triggerOriginTimestamp);
    }

    public void stamp(final ModifyOrderEncoder modify) {
        modify.intentSequence(intentSequence)
                .triggerSequence(triggerSequence)
                .triggerKind(triggerKind)
                .triggerTimestamp(triggerTimestamp)
                .triggerOriginTimestamp(triggerOriginTimestamp);
    }

    public void stamp(final CancelOrderEncoder cancel) {
        cancel.intentSequence(intentSequence)
                .triggerSequence(triggerSequence)
                .triggerKind(triggerKind)
                .triggerTimestamp(triggerTimestamp)
                .triggerOriginTimestamp(triggerOriginTimestamp);
    }

    public long intentSequence() {
        return intentSequence;
    }

    public long triggerSequence() {
        return triggerSequence;
    }

    public TriggerKind triggerKind() {
        return triggerKind;
    }

    public long triggerTimestamp() {
        return triggerTimestamp;
    }

    public long triggerOriginTimestamp() {
        return triggerOriginTimestamp;
    }
}
