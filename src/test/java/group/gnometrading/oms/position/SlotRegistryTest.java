package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlotRegistryTest {

    private SlotRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SlotRegistry(8);
    }

    @Test
    void count_initiallyZero() {
        assertEquals(0, registry.count());
    }

    @Test
    void register_singleSlot() {
        registry.register(0, 1, 100);

        assertEquals(1, registry.count());
        assertEquals(1, registry.strategyId(0));
        assertEquals(100, registry.listingId(0));
    }

    @Test
    void register_multipleSlots() {
        registry.register(0, 1, 100);
        registry.register(1, 1, 101);
        registry.register(2, 2, 100);

        assertEquals(3, registry.count());
        assertEquals(1, registry.strategyId(0));
        assertEquals(100, registry.listingId(0));
        assertEquals(1, registry.strategyId(1));
        assertEquals(101, registry.listingId(1));
        assertEquals(2, registry.strategyId(2));
        assertEquals(100, registry.listingId(2));
    }

    @Test
    void count_reflectsHighestSlotPlusOne() {
        registry.register(3, 5, 200);
        assertEquals(4, registry.count());
    }
}
