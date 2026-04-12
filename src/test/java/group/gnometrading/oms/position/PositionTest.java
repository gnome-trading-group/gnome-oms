package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PositionTest {

    private Position position;

    @BeforeEach
    void setUp() {
        position = new Position();
        position.init(42);
    }

    // --- init ---

    @Test
    void init_resetsAllFields() {
        position.netQuantity = 99;
        position.totalCost = 999;
        position.realizedPnl = 111;
        position.totalFees = 5;
        position.leavesBuyQty = 3;
        position.leavesSellQty = 7;
        position.sharedSlot = 2;

        position.init(42);

        assertEquals(42, position.listingId);
        assertEquals(0, position.netQuantity);
        assertEquals(0, position.totalCost);
        assertEquals(0, position.realizedPnl);
        assertEquals(0, position.totalFees);
        assertEquals(0, position.leavesBuyQty);
        assertEquals(0, position.leavesSellQty);
        assertEquals(-1, position.sharedSlot);
    }

    // --- applyFill: opening positions ---

    @Test
    void applyFill_openLongFromFlat() {
        position.applyFill(Side.Bid, 10, 100, 0);

        assertEquals(10, position.netQuantity);
        assertEquals(1000, position.totalCost);
        assertEquals(0, position.realizedPnl);
    }

    @Test
    void applyFill_openShortFromFlat() {
        position.applyFill(Side.Ask, 10, 100, 0);

        assertEquals(-10, position.netQuantity);
        assertEquals(1000, position.totalCost);
        assertEquals(0, position.realizedPnl);
    }

    // --- applyFill: adding to existing position ---

    @Test
    void applyFill_addToLong() {
        position.applyFill(Side.Bid, 10, 100, 0);
        position.applyFill(Side.Bid, 5, 120, 0);

        assertEquals(15, position.netQuantity);
        assertEquals(1600, position.totalCost);
        assertEquals(0, position.realizedPnl);
    }

    @Test
    void applyFill_addToShort() {
        position.applyFill(Side.Ask, 10, 100, 0);
        position.applyFill(Side.Ask, 5, 120, 0);

        assertEquals(-15, position.netQuantity);
        assertEquals(1600, position.totalCost);
        assertEquals(0, position.realizedPnl);
    }

    // --- applyFill: partial close ---

    @Test
    void applyFill_partialCloseLong() {
        position.applyFill(Side.Bid, 10, 100, 0); // long 10 @ avg 100
        position.applyFill(Side.Ask, 5, 120, 0); // sell 5 @ 120

        assertEquals(5, position.netQuantity);
        assertEquals(500, position.totalCost); // avgEntry(100) * 5
        assertEquals(100, position.realizedPnl); // 5 * (120 - 100)
    }

    @Test
    void applyFill_partialCloseShort() {
        position.applyFill(Side.Ask, 10, 100, 0); // short 10 @ avg 100
        position.applyFill(Side.Bid, 5, 80, 0); // buy 5 @ 80

        assertEquals(-5, position.netQuantity);
        assertEquals(500, position.totalCost); // avgEntry(100) * 5
        assertEquals(100, position.realizedPnl); // 5 * (100 - 80)
    }

    // --- applyFill: full close ---

    @Test
    void applyFill_fullCloseLong() {
        position.applyFill(Side.Bid, 10, 100, 0);
        position.applyFill(Side.Ask, 10, 120, 0);

        assertEquals(0, position.netQuantity);
        assertEquals(0, position.totalCost);
        assertEquals(200, position.realizedPnl); // 10 * (120 - 100)
    }

    @Test
    void applyFill_fullCloseShort() {
        position.applyFill(Side.Ask, 10, 100, 0);
        position.applyFill(Side.Bid, 10, 80, 0);

        assertEquals(0, position.netQuantity);
        assertEquals(0, position.totalCost);
        assertEquals(200, position.realizedPnl); // 10 * (100 - 80)
    }

    // --- applyFill: flip ---

    @Test
    void applyFill_flipLongToShort() {
        position.applyFill(Side.Bid, 10, 100, 0); // long 10 @ 100
        position.applyFill(Side.Ask, 15, 120, 0); // sell 15 @ 120 -> close 10, open short 5

        assertEquals(-5, position.netQuantity);
        assertEquals(600, position.totalCost); // 120 * 5 (new short opened at fill price)
        assertEquals(200, position.realizedPnl); // 10 * (120 - 100)
    }

    @Test
    void applyFill_flipShortToLong() {
        position.applyFill(Side.Ask, 10, 100, 0); // short 10 @ 100
        position.applyFill(Side.Bid, 15, 80, 0); // buy 15 @ 80 -> close 10, open long 5

        assertEquals(5, position.netQuantity);
        assertEquals(400, position.totalCost); // 80 * 5 (new long opened at fill price)
        assertEquals(200, position.realizedPnl); // 10 * (100 - 80)
    }

    // --- applyFill: fees ---

    @Test
    void applyFill_accumulatesFees() {
        position.applyFill(Side.Bid, 10, 100, 3);
        position.applyFill(Side.Ask, 5, 120, 7);

        assertEquals(10, position.totalFees);
    }

    // --- applyFill: sequential scenario ---

    @Test
    void applyFill_buildAndUnwindPosition() {
        position.applyFill(Side.Bid, 10, 100, 1); // long 10 @ 100
        position.applyFill(Side.Bid, 5, 110, 1); // long 15, avg = (1000+550)/15 = 103.33...
        position.applyFill(Side.Ask, 8, 120, 1); // close 8 of 15

        // avgEntry after 2 buys = 1550/15 = 103
        long avgEntry = 1550 / 15;
        assertEquals(7, position.netQuantity);
        assertEquals(avgEntry * 7, position.totalCost);
        assertEquals(8 * (120 - avgEntry), position.realizedPnl);
        assertEquals(3, position.totalFees);
    }

    // --- getAvgEntryPrice ---

    @Test
    void getAvgEntryPrice_flatReturnsZero() {
        assertEquals(0, position.getAvgEntryPrice());
    }

    @Test
    void getAvgEntryPrice_longPosition() {
        position.applyFill(Side.Bid, 10, 100, 0);
        assertEquals(100, position.getAvgEntryPrice());
    }

    @Test
    void getAvgEntryPrice_shortPosition() {
        position.applyFill(Side.Ask, 10, 100, 0);
        assertEquals(100, position.getAvgEntryPrice());
    }

    @Test
    void getAvgEntryPrice_weightedAfterMultipleBuys() {
        position.applyFill(Side.Bid, 10, 100, 0);
        position.applyFill(Side.Bid, 10, 200, 0);
        // totalCost = 3000, netQty = 20
        assertEquals(150, position.getAvgEntryPrice());
    }

    // --- addLeaves / removeLeaves ---

    @Test
    void addLeaves_bid() {
        position.addLeaves(Side.Bid, 5);
        assertEquals(5, position.leavesBuyQty);
        assertEquals(0, position.leavesSellQty);
    }

    @Test
    void addLeaves_ask() {
        position.addLeaves(Side.Ask, 5);
        assertEquals(0, position.leavesBuyQty);
        assertEquals(5, position.leavesSellQty);
    }

    @Test
    void removeLeaves_bid() {
        position.addLeaves(Side.Bid, 10);
        position.removeLeaves(Side.Bid, 4);
        assertEquals(6, position.leavesBuyQty);
    }

    @Test
    void removeLeaves_floorsAtZero() {
        position.addLeaves(Side.Ask, 3);
        position.removeLeaves(Side.Ask, 10);
        assertEquals(0, position.leavesSellQty);
    }

    // --- getEffectiveQuantity ---

    @Test
    void getEffectiveQuantity_noLeaves() {
        position.applyFill(Side.Bid, 10, 100, 0);
        assertEquals(10, position.getEffectiveQuantity());
    }

    @Test
    void getEffectiveQuantity_withLeaves() {
        position.applyFill(Side.Bid, 10, 100, 0);
        position.addLeaves(Side.Bid, 3);
        position.addLeaves(Side.Ask, 2);
        assertEquals(11, position.getEffectiveQuantity()); // 10 + 3 - 2
    }

    // --- setFromBuffer ---

    @Test
    void setFromBuffer_setsAllFields() {
        position.setFromBuffer(5, 500, 100, 10, 3, 7);

        assertEquals(5, position.netQuantity);
        assertEquals(500, position.totalCost);
        assertEquals(100, position.realizedPnl);
        assertEquals(10, position.totalFees);
        assertEquals(3, position.leavesBuyQty);
        assertEquals(7, position.leavesSellQty);
    }
}
