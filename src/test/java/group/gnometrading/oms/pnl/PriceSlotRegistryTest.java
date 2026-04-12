package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.collections.IntToIntHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceSlotRegistryTest {

    private PriceSlotRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PriceSlotRegistry(8);
    }

    @Test
    void count_initiallyZero() {
        assertEquals(0, registry.count());
    }

    @Test
    void register_returnsDenseSlotIndex() {
        assertEquals(0, registry.register(100));
        assertEquals(1, registry.register(101));
        assertEquals(2, registry.register(200));
    }

    @Test
    void register_incrementsCount() {
        registry.register(100);
        registry.register(101);
        assertEquals(2, registry.count());
    }

    @Test
    void getSlot_returnsRegisteredSlot() {
        int slot = registry.register(100);
        assertEquals(slot, registry.getSlot(100));
    }

    @Test
    void getSlot_returnsMissingForUnknownListing() {
        assertEquals(IntToIntHashMap.MISSING, registry.getSlot(999));
    }

    @Test
    void listingId_returnsCorrectListingForSlot() {
        registry.register(100);
        registry.register(200);
        assertEquals(100, registry.listingId(0));
        assertEquals(200, registry.listingId(1));
    }

    @Test
    void multipleListings_areIndependent() {
        int slot100 = registry.register(100);
        int slot200 = registry.register(200);

        assertEquals(slot100, registry.getSlot(100));
        assertEquals(slot200, registry.getSlot(200));
        assertEquals(100, registry.listingId(slot100));
        assertEquals(200, registry.listingId(slot200));
    }
}
