package group.gnometrading.oms.position;

public final class SlotRegistry {

    private int count;
    private final int[] strategyIds;
    private final int[] listingIds;

    public SlotRegistry(int maxSlots) {
        this.strategyIds = new int[maxSlots];
        this.listingIds = new int[maxSlots];
    }

    public void register(int slot, int strategyId, int listingId) {
        strategyIds[slot] = strategyId;
        listingIds[slot] = listingId;
        count = slot + 1;
    }

    public int count() {
        return count;
    }

    public int strategyId(int slot) {
        return strategyIds[slot];
    }

    public int listingId(int slot) {
        return listingIds[slot];
    }
}
