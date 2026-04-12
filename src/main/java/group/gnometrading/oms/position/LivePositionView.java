package group.gnometrading.oms.position;

import group.gnometrading.collections.IntToIntMap;

/**
 * Strategy-side {@link PositionView} backed by a {@link SharedPositionBuffer}.
 *
 * <p>Reads use a seqlock spin until a consistent snapshot is obtained. Zero allocation
 * on every read — the returned {@link Position} is a pre-allocated flyweight valid only
 * until the next call to {@link #getPosition}.
 *
 * <p>Constructed via {@link DefaultPositionTracker#createPositionView(int)} after all
 * slots have been registered.
 */
public final class LivePositionView implements PositionView {

    private final SharedPositionBuffer buffer;
    private final IntToIntMap slotByListingId;
    private final Position flyweight = new Position();

    LivePositionView(SharedPositionBuffer buffer, IntToIntMap slotByListingId) {
        this.buffer = buffer;
        this.slotByListingId = slotByListingId;
    }

    @Override
    public Position getPosition(int listingId) {
        int slot = slotByListingId.get(listingId);
        buffer.readSpinning(slot, flyweight);
        return flyweight;
    }
}
