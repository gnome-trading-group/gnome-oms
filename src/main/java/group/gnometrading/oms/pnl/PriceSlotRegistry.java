package group.gnometrading.oms.pnl;

import group.gnometrading.collections.IntToIntHashMap;

/**
 * Maps listing IDs to dense slot indices in {@link SharedPriceBuffer}.
 *
 * <p>Unlike {@link group.gnometrading.oms.position.SlotRegistry} which tracks
 * (strategyId, listingId) pairs, price slots are per-listing only — the same
 * mark price is shared across all strategies trading that listing.
 */
public final class PriceSlotRegistry {

    private int count;
    private final int[] listingIds;
    private final IntToIntHashMap slotByListingId;

    public PriceSlotRegistry(int maxSlots) {
        this.listingIds = new int[maxSlots];
        this.slotByListingId = new IntToIntHashMap(maxSlots);
    }

    /**
     * Registers a listing and returns its assigned slot index. Call at startup only.
     */
    public int register(int listingId) {
        int slot = count++;
        listingIds[slot] = listingId;
        slotByListingId.put(listingId, slot);
        return slot;
    }

    /**
     * Returns the slot for the given listing, or {@link IntToIntHashMap#MISSING} if not registered.
     */
    public int getSlot(int listingId) {
        return slotByListingId.get(listingId);
    }

    public int count() {
        return count;
    }

    public int listingId(int slot) {
        return listingIds[slot];
    }
}
