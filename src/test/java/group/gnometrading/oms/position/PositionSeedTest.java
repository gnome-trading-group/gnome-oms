package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.Test;

class PositionSeedTest {

    private static final long UNIT = 1_000_000L;
    private static final long CENT = 10_000_000L;

    @Test
    void aSeededPositionCarriesOnFromItsInventoryAndVersion() {
        final SharedPositionBuffer shared = new SharedPositionBuffer(8);
        final DefaultPositionTracker tracker = new DefaultPositionTracker(shared);
        tracker.registerSlot(7, 100);

        // Inherited: short 10 at 40c.
        tracker.seedStrategyPosition(7, 100, -10 * UNIT, 400 * CENT, 12);
        final Position seeded = tracker.getStrategyPosition(7, 100);
        assertEquals(-10 * UNIT, seeded.netQuantity);
        assertEquals(40 * CENT, seeded.getAvgEntryPrice());
        assertEquals(0, seeded.realizedPnl, "realized PnL belongs to the session that earned it");
        assertEquals(-10 * UNIT, tracker.getPosition(100).netQuantity, "the firm holds it too");
        final Position read = new Position();
        shared.readSpinning(0, read);
        assertEquals(-10 * UNIT, read.netQuantity, "strategies see it");

        // Buying 4 back at 30c closes part of the short at a 10c profit each.
        tracker.applyStrategyFill(7, 100, Side.Bid, 4 * UNIT, 30 * CENT, 0);
        assertEquals(13, seeded.version);
        assertEquals(-6 * UNIT, seeded.netQuantity);
        assertEquals(40 * CENT, seeded.realizedPnl);
    }
}
