package group.gnometrading.oms.position;

/**
 * Strategy-side {@link PositionView} backed by a {@link SharedPositionBuffer}.
 *
 * <p>Reads use a seqlock spin until a consistent snapshot is obtained. Zero allocation
 * on every read — the returned {@link Position} is a pre-allocated flyweight valid only
 * until the next call to {@link #getPosition} or {@link #getEffectiveQuantity}.
 *
 * <p>The {@code slotByListingId} array must be pre-built at startup via
 * {@link DefaultPositionTracker#registerSlot} before any strategy thread reads.
 */
public final class LivePositionView implements PositionView {

    private final SharedPositionBuffer buffer;
    private final int[] slotByListingId;
    private final Position flyweight = new Position();

    /**
     * @param buffer          the shared buffer written by the OMS thread
     * @param slotByListingId array where {@code slotByListingId[listingId]} is the assigned slot index;
     *                        unregistered entries must be -1
     */
    public LivePositionView(SharedPositionBuffer buffer, int[] slotByListingId) {
        this.buffer = buffer;
        this.slotByListingId = slotByListingId;
    }

    @Override
    public Position getPosition(int listingId) {
        int slot = slotByListingId[listingId];
        buffer.readSpinning(slot, flyweight);
        return flyweight;
    }

    @Override
    public long getEffectiveQuantity(int listingId) {
        int slot = slotByListingId[listingId];
        buffer.readSpinning(slot, flyweight);
        return flyweight.getEffectiveQuantity();
    }
}
