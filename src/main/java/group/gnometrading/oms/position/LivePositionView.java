package group.gnometrading.oms.position;

import group.gnometrading.collections.IntHashMap;

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
    private final IntHashMap<Integer> slotByListingId;
    private final Position flyweight = new Position();

    LivePositionView(SharedPositionBuffer buffer, IntHashMap<Integer> slotByListingId) {
        this.buffer = buffer;
        this.slotByListingId = slotByListingId;
    }

    @Override
    public Position getPosition(int listingId) {
        Integer slot = slotByListingId.get(listingId);
        buffer.readSpinning(slot, flyweight);
        return flyweight;
    }
}
