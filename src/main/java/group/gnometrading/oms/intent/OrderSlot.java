package group.gnometrading.oms.intent;

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
 */
public final class OrderSlot {

    public enum State {
        EMPTY,
        PENDING_NEW,
        LIVE,
        PENDING_MODIFY,
        PENDING_CANCEL
    }

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

    public OrderSlot(boolean nativeModify) {
        this.nativeModify = nativeModify;
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
    }

    public void onNewAcked() {
        this.state = State.LIVE;
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
    }

    public void queueIntent(long price, long size, short flags) {
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
