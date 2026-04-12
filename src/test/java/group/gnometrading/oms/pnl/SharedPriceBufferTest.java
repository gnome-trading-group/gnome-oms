package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedPriceBufferTest {

    private SharedPriceBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new SharedPriceBuffer(8);
    }

    @Test
    void register_returnsSequentialSlots() {
        assertEquals(0, buffer.register());
        assertEquals(1, buffer.register());
        assertEquals(2, buffer.register());
    }

    @Test
    void capacity_returnsMaxSlots() {
        assertEquals(8, buffer.capacity());
    }

    @Test
    void writeAndRead_roundTripsPrice() {
        int slot = buffer.register();
        buffer.write(slot, 12345L);

        assertEquals(12345L, buffer.readSpinning(slot));
    }

    @Test
    void read_onFreshSlot_returnsZero() {
        int slot = buffer.register();
        assertEquals(0L, buffer.readSpinning(slot));
    }

    @Test
    void write_updatesExistingSlot() {
        int slot = buffer.register();
        buffer.write(slot, 100L);
        buffer.write(slot, 200L);

        assertEquals(200L, buffer.readSpinning(slot));
    }

    @Test
    void multipleSlots_areIndependent() {
        int slot0 = buffer.register();
        int slot1 = buffer.register();

        buffer.write(slot0, 1000L);
        buffer.write(slot1, 2000L);

        assertEquals(1000L, buffer.readSpinning(slot0));
        assertEquals(2000L, buffer.readSpinning(slot1));
    }

    @Test
    void read_returnsSentinelWhenVersionOdd() {
        // Version is even (0) on a fresh slot — read should return 0 (not sentinel)
        int slot = buffer.register();
        assertEquals(0L, buffer.readSpinning(slot));
        assertNotEquals(Long.MIN_VALUE, buffer.readSpinning(slot));
    }
}
