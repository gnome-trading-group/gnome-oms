package group.gnometrading.oms.intent;

import group.gnometrading.oms.OrderCause;

/**
 * Tracks the lifecycle state of a single order slot (one per side per instrument per strategy).
 * Ensures only one order is live or pending at a time on each side.
 *
 * <p>Quantities follow FIX: the order quantity is the order's total size including what has filled,
 * and the resting quantity is that less the fills seen. Strategies express intents as resting size;
 * keeping the order quantity total is what lets a modify stay correct while fills are still landing.
 *
 * <p>State machine:
 * EMPTY → PENDING_NEW → LIVE → PENDING_MODIFY → LIVE
 *                            → PENDING_CANCEL → EMPTY
 *
 * <p>On a venue without native modify a slot never enters PENDING_MODIFY: changing a live order
 * cancels it and submits the target as a new order once the cancel is confirmed.
 *
 * <p>A pull, or a change on a venue without native modify, cancels a PENDING_NEW order at once rather
 * than waiting for its NEW, which can trail the venue's acceptance by hundreds of milliseconds; its NEW
 * then moves the slot to PENDING_CANCEL.
 *
 * <p>An order or amend refused for the venue's rate limit starts a cooldown, doubling with each
 * refusal in a row, during which the slot sends nothing new; resending on every intent would only
 * be refused again. Cancels are never held back.
 */
public final class OrderSlot {

    static final long INITIAL_COOLDOWN_NANOS = 100_000_000L;
    static final long MAX_COOLDOWN_NANOS = 2_000_000_000L;
    static final long CANCEL_RETRY_FIRST_NANOS = 100_000_000L;
    static final long CANCEL_RETRY_MAX_NANOS = 2_000_000_000L;

    public enum State {
        EMPTY,
        PENDING_NEW,
        LIVE,
        PENDING_MODIFY,
        PENDING_CANCEL
    }

    private final int listingId;
    private final int exchangeId;
    private final long securityId;
    private final boolean nativeModify;

    private State state = State.EMPTY;
    private long activeClientOid;
    private long activePrice;
    private long activeOrderQty;
    private long filledQty;

    private long pendingModifyPrice;
    private long pendingModifyOrderQty;

    private short activeFlags;

    // Queued intent: what the strategy wants next, stored while a pending state is in-flight
    private long queuedPrice;
    private long queuedSize;
    private short queuedFlags;
    private boolean hasQueuedIntent;
    // Why the queued intent was wanted, stamped on what is finally sent for it.
    private final OrderCause queuedCause = new OrderCause();

    private long cooldownStepNanos;
    private long cooldownUntilNanos;
    // A cancel already went out for the order while its NEW was unanswered, so its ack must not make it LIVE.
    private boolean cancelSentBeforeAck;
    // The strategy's latest intent, or a kill, wants the order gone. A refused cancel of it, or of one a cancel-replace
    // is moving, is sent again after a pause, doubling while refusals continue.
    private boolean pullWanted;
    private boolean cancelRetryScheduled;
    // The order the scheduled retry is for: a retry that comes due after the slot moved to another order is dropped.
    private long cancelRetryOid;
    private long cancelRetryAtNanos;
    private long cancelRetryStepNanos;

    public OrderSlot(boolean nativeModify, int listingId, int exchangeId, long securityId) {
        this.nativeModify = nativeModify;
        this.listingId = listingId;
        this.exchangeId = exchangeId;
        this.securityId = securityId;
    }

    public int getListingId() {
        return listingId;
    }

    public int getExchangeId() {
        return exchangeId;
    }

    public long getSecurityId() {
        return securityId;
    }

    public boolean supportsNativeModify() {
        return nativeModify;
    }

    public State getState() {
        return state;
    }

    public long getActiveClientOid() {
        return activeClientOid;
    }

    public boolean canSubmitNew() {
        return state == State.EMPTY;
    }

    public boolean isLive() {
        return state == State.LIVE;
    }

    public boolean isPendingCancel() {
        return state == State.PENDING_CANCEL;
    }

    public void onNewSubmitted(long clientOid, long price, long orderQty, short flags) {
        this.state = State.PENDING_NEW;
        this.activeClientOid = clientOid;
        this.activePrice = price;
        this.activeOrderQty = orderQty;
        this.filledQty = 0;
        this.activeFlags = flags;
        this.cancelSentBeforeAck = false;
        clearPull();
    }

    public void onNewAcked() {
        this.state = State.LIVE;
        this.cooldownStepNanos = 0;
    }

    /** A cancel went out for the order while it is still PENDING_NEW. */
    public void onCancelSentBeforeAck() {
        this.cancelSentBeforeAck = true;
    }

    public boolean isCancelSentBeforeAck() {
        return cancelSentBeforeAck;
    }

    /** The order is wanted gone, by a pull or a kill. */
    public void markPullWanted() {
        this.pullWanted = true;
    }

    /** An intent wants an order again: no longer a pull, and the next refusal's pause starts over. */
    public void clearPullWanted() {
        this.pullWanted = false;
        this.cancelRetryStepNanos = 0;
    }

    public boolean isPullWanted() {
        return pullWanted;
    }

    /**
     * The venue refused a cancel worth retrying: schedules it to be sent again, after 100 ms and doubling
     * up to 2 s while refusals continue. Returns whether a retry was newly scheduled, false if one already was.
     */
    public boolean scheduleCancelRetry(long nowNanos) {
        this.cancelRetryStepNanos = this.cancelRetryStepNanos == 0
                ? CANCEL_RETRY_FIRST_NANOS
                : Math.min(2 * this.cancelRetryStepNanos, CANCEL_RETRY_MAX_NANOS);
        this.cancelRetryAtNanos = nowNanos + this.cancelRetryStepNanos;
        this.cancelRetryOid = this.activeClientOid;
        if (this.cancelRetryScheduled) {
            return false;
        }
        this.cancelRetryScheduled = true;
        return true;
    }

    /** Whether the retry that came due is still for the order the slot works. */
    public boolean isCancelRetryForActiveOrder() {
        return cancelRetryOid == activeClientOid;
    }

    /**
     * A replacement waits behind a refused cancel: on a venue without native modify, a queued order that differs
     * from what the slot works.
     */
    public boolean isReplacementWaiting() {
        return !nativeModify
                && hasQueuedIntent
                && queuedSize > 0
                && (queuedPrice != activePrice || queuedSize != getRestingQty());
    }

    public boolean isCancelRetryScheduled() {
        return cancelRetryScheduled;
    }

    public long getCancelRetryAtNanos() {
        return cancelRetryAtNanos;
    }

    /** The scheduled retry has come due and is being dealt with. */
    public void onCancelRetryDue() {
        this.cancelRetryScheduled = false;
    }

    private void clearPull() {
        this.pullWanted = false;
        this.cancelRetryStepNanos = 0;
    }

    /** The NEW of an order already being cancelled: the venue took it, and the cancel sent before is now pending. */
    public void onNewAckedWhileCancelling() {
        this.state = State.PENDING_CANCEL;
        this.cooldownStepNanos = 0;
        this.cancelSentBeforeAck = false;
    }

    /** The venue refused the cancel sent before the NEW: the order stays PENDING_NEW, with no cancel out. */
    public void onCancelBeforeAckRejected() {
        this.cancelSentBeforeAck = false;
    }

    public void onCancelSubmitted() {
        this.state = State.PENDING_CANCEL;
    }

    public long getActivePrice() {
        return activePrice;
    }

    public long getActiveOrderQty() {
        return activeOrderQty;
    }

    public long getFilledQty() {
        return filledQty;
    }

    /** What is still working on the venue: the order quantity less the fills seen. */
    public long getRestingQty() {
        return Math.max(0, activeOrderQty - filledQty);
    }

    /** The FIX order quantity a modify must carry for {@code restingQty} to be left working. */
    public long orderQtyForResting(long restingQty) {
        return restingQty + filledQty;
    }

    /** Records the order's cumulative fill. Monotonic, so a stale or repeated report cannot regress it. */
    public void onCumulativeQty(long cumulativeQty) {
        this.filledQty = Math.max(this.filledQty, cumulativeQty);
    }

    public void onModifySubmitted(long pendingPrice, long pendingOrderQty) {
        this.state = State.PENDING_MODIFY;
        this.pendingModifyPrice = pendingPrice;
        this.pendingModifyOrderQty = pendingOrderQty;
    }

    public void onModifyConfirmed() {
        this.state = State.LIVE;
        this.cooldownStepNanos = 0;
        this.activePrice = this.pendingModifyPrice;
        this.activeOrderQty = this.pendingModifyOrderQty;
        this.pendingModifyPrice = 0;
        this.pendingModifyOrderQty = 0;
    }

    public void onModifyRejected() {
        this.state = State.LIVE;
        this.pendingModifyPrice = 0;
        this.pendingModifyOrderQty = 0;
    }

    public void onCancelRejected() {
        this.state = State.LIVE;
    }

    public short getActiveFlags() {
        return activeFlags;
    }

    public short getQueuedFlags() {
        return queuedFlags;
    }

    public void onTerminal() {
        this.state = State.EMPTY;
        this.activeClientOid = 0;
        this.activePrice = 0;
        this.activeOrderQty = 0;
        this.filledQty = 0;
        this.activeFlags = 0;
        this.pendingModifyPrice = 0;
        this.pendingModifyOrderQty = 0;
        this.cancelSentBeforeAck = false;
        clearPull();
    }

    /** The venue refused this slot's order or amend for its rate limit. */
    public void onRateLimited(long nowNanos) {
        this.cooldownStepNanos = this.cooldownStepNanos == 0
                ? INITIAL_COOLDOWN_NANOS
                : Math.min(MAX_COOLDOWN_NANOS, this.cooldownStepNanos * 2);
        this.cooldownUntilNanos = nowNanos + this.cooldownStepNanos;
    }

    /** Whether a rate-limit refusal has been seen since the venue last accepted this slot's order. */
    public boolean hasCooldown() {
        return this.cooldownStepNanos != 0;
    }

    /** Whether new orders and amends are held back at {@code nowNanos}. */
    public boolean isCoolingDown(long nowNanos) {
        return this.cooldownStepNanos != 0 && this.cooldownUntilNanos - nowNanos > 0;
    }

    public void queueIntent(long price, long size, short flags, OrderCause cause) {
        this.queuedCause.copyFrom(cause);
        this.queuedPrice = price;
        this.queuedSize = size;
        this.queuedFlags = flags;
        this.hasQueuedIntent = true;
    }

    public void clearQueuedIntent() {
        this.hasQueuedIntent = false;
        this.queuedPrice = 0;
        this.queuedSize = 0;
        this.queuedFlags = 0;
    }

    public OrderCause getQueuedCause() {
        return queuedCause;
    }

    public boolean hasQueuedIntent() {
        return hasQueuedIntent;
    }

    public long getQueuedPrice() {
        return queuedPrice;
    }

    public long getQueuedSize() {
        return queuedSize;
    }
}
