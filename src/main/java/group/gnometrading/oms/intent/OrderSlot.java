package group.gnometrading.oms.intent;

/**
 * Tracks the lifecycle state of a single order slot (one per side per instrument per strategy).
 * Ensures only one order is live or pending at a time on each side.
 *
 * <p>State machine:
 * EMPTY → PENDING_NEW → LIVE → PENDING_CANCEL → EMPTY
 */
public final class OrderSlot {

    public enum State {
        EMPTY,
        PENDING_NEW,
        LIVE,
        PENDING_CANCEL
    }

    private State state = State.EMPTY;
    private long activeClientOid;
    private long activePrice;
    private long activeSize;

    // Queued intent: what the strategy wants next, stored while PENDING_CANCEL
    private long queuedPrice;
    private long queuedSize;
    private boolean hasQueuedIntent;

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

    public void onNewSubmitted(long clientOid, long price, long size) {
        this.state = State.PENDING_NEW;
        this.activeClientOid = clientOid;
        this.activePrice = price;
        this.activeSize = size;
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

    public long getActiveSize() {
        return activeSize;
    }

    public void onAmendAcked(long newPrice, long newSize) {
        this.activePrice = newPrice;
        this.activeSize = newSize;
    }

    public void onTerminal() {
        this.state = State.EMPTY;
        this.activeClientOid = 0;
        this.activePrice = 0;
        this.activeSize = 0;
    }

    public void queueIntent(long price, long size) {
        this.queuedPrice = price;
        this.queuedSize = size;
        this.hasQueuedIntent = true;
    }

    public void clearQueuedIntent() {
        this.hasQueuedIntent = false;
        this.queuedPrice = 0;
        this.queuedSize = 0;
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
