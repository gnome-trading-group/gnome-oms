package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedPositionBufferTest {

    private SharedPositionBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new SharedPositionBuffer(8);
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
    void writeAndRead_roundTripsAllFields() {
        int slot = buffer.register();

        Position src = new Position();
        src.init(100);
        src.netQuantity = 10;
        src.totalCost = 1000;
        src.realizedPnl = 200;
        src.totalFees = 5;
        src.leavesBuyQty = 3;
        src.leavesSellQty = 7;

        buffer.write(slot, src);

        Position dest = new Position();
        assertTrue(buffer.read(slot, dest));

        assertEquals(10, dest.netQuantity);
        assertEquals(1000, dest.totalCost);
        assertEquals(200, dest.realizedPnl);
        assertEquals(5, dest.totalFees);
        assertEquals(3, dest.leavesBuyQty);
        assertEquals(7, dest.leavesSellQty);
    }

    @Test
    void read_onFreshSlot_returnsTrueWithZeroValues() {
        int slot = buffer.register();
        Position dest = new Position();
        dest.init(0);

        assertTrue(buffer.read(slot, dest));

        assertEquals(0, dest.netQuantity);
        assertEquals(0, dest.totalCost);
        assertEquals(0, dest.realizedPnl);
    }

    @Test
    void readSpinning_returnsSameDataAsRead() {
        int slot = buffer.register();

        Position src = new Position();
        src.netQuantity = -5;
        src.totalCost = 500;
        src.realizedPnl = 50;
        src.totalFees = 2;
        src.leavesBuyQty = 0;
        src.leavesSellQty = 4;
        buffer.write(slot, src);

        Position dest = new Position();
        buffer.readSpinning(slot, dest);

        assertEquals(-5, dest.netQuantity);
        assertEquals(500, dest.totalCost);
        assertEquals(50, dest.realizedPnl);
        assertEquals(2, dest.totalFees);
        assertEquals(4, dest.leavesSellQty);
    }

    @Test
    void multipleSlots_areIndependent() {
        int slot0 = buffer.register();
        int slot1 = buffer.register();

        Position pos0 = new Position();
        pos0.netQuantity = 10;
        pos0.totalCost = 100;
        buffer.write(slot0, pos0);

        Position pos1 = new Position();
        pos1.netQuantity = -20;
        pos1.totalCost = 200;
        buffer.write(slot1, pos1);

        Position dest0 = new Position();
        Position dest1 = new Position();
        buffer.readSpinning(slot0, dest0);
        buffer.readSpinning(slot1, dest1);

        assertEquals(10, dest0.netQuantity);
        assertEquals(100, dest0.totalCost);
        assertEquals(-20, dest1.netQuantity);
        assertEquals(200, dest1.totalCost);
    }

    @Test
    void write_updatesExistingSlot() {
        int slot = buffer.register();

        Position first = new Position();
        first.netQuantity = 5;
        first.totalCost = 500;
        buffer.write(slot, first);

        Position second = new Position();
        second.netQuantity = 15;
        second.totalCost = 1500;
        second.realizedPnl = 100;
        buffer.write(slot, second);

        Position dest = new Position();
        buffer.readSpinning(slot, dest);

        assertEquals(15, dest.netQuantity);
        assertEquals(1500, dest.totalCost);
        assertEquals(100, dest.realizedPnl);
    }
}
