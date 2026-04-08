package group.gnometrading.oms.position;

import group.gnometrading.utils.ByteBufferUtils;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Shared off-heap buffer for zero-GC cross-thread position reads.
 *
 * <p>Each slot is exactly 64 bytes (one cache line) to eliminate false sharing.
 * The OMS thread writes position snapshots via a seqlock; strategy threads read
 * with seqlock retry until a consistent snapshot is obtained.
 *
 * <p>Slot layout:
 * <pre>
 *   [0]  version       (long) — odd = writer active, even = stable
 *   [8]  netQuantity   (long)
 *   [16] avgEntryPrice (long)
 *   [24] realizedPnl   (long) — Double.doubleToRawLongBits
 *   [32] totalFees     (long)
 *   [40] leavesBuyQty  (long)
 *   [48] leavesSellQty (long)
 *   [56] padding       (long)
 * </pre>
 */
public final class SharedPositionBuffer {

    private static final int SLOT_SIZE = 64;
    private static final int VERSION_OFFSET = 0;
    private static final int NET_QTY_OFFSET = 8;
    private static final int AVG_ENTRY_OFFSET = 16;
    private static final int REALIZED_PNL_OFFSET = 24;
    private static final int TOTAL_FEES_OFFSET = 32;
    private static final int LEAVES_BUY_OFFSET = 40;
    private static final int LEAVES_SELL_OFFSET = 48;

    private final UnsafeBuffer buffer;
    private int nextSlot = 0;

    public SharedPositionBuffer(int maxSlots) {
        this.buffer = ByteBufferUtils.createAlignedUnsafeBuffer(maxSlots * SLOT_SIZE);
    }

    /**
     * Assigns and returns the next dense slot index. Call at startup only.
     */
    public int register() {
        return nextSlot++;
    }

    /**
     * Writes a full position snapshot with seqlock ordering.
     * Must only be called from the OMS thread.
     */
    public void write(int slot, Position pos) {
        int base = slot * SLOT_SIZE;
        long version = buffer.getLong(base + VERSION_OFFSET);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 1); // odd = writing
        buffer.putLong(base + NET_QTY_OFFSET, pos.netQuantity);
        buffer.putLong(base + AVG_ENTRY_OFFSET, pos.avgEntryPrice);
        buffer.putLong(base + REALIZED_PNL_OFFSET, Double.doubleToRawLongBits(pos.realizedPnl));
        buffer.putLong(base + TOTAL_FEES_OFFSET, pos.totalFees);
        buffer.putLong(base + LEAVES_BUY_OFFSET, pos.leavesBuyQty);
        buffer.putLong(base + LEAVES_SELL_OFFSET, pos.leavesSellQty);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 2); // even = stable
    }

    /**
     * Attempts a single seqlock read into {@code dest}.
     *
     * @return true if the read was consistent, false if a writer was active or a torn read occurred
     */
    public boolean read(int slot, Position dest) {
        int base = slot * SLOT_SIZE;
        long v1 = buffer.getLongVolatile(base + VERSION_OFFSET);
        if ((v1 & 1) != 0) {
            return false;
        }
        long netQty = buffer.getLong(base + NET_QTY_OFFSET);
        long avgEntry = buffer.getLong(base + AVG_ENTRY_OFFSET);
        long realizedPnlBits = buffer.getLong(base + REALIZED_PNL_OFFSET);
        long totalFees = buffer.getLong(base + TOTAL_FEES_OFFSET);
        long leavesBuy = buffer.getLong(base + LEAVES_BUY_OFFSET);
        long leavesSell = buffer.getLong(base + LEAVES_SELL_OFFSET);
        long v2 = buffer.getLongVolatile(base + VERSION_OFFSET);
        if (v1 != v2) {
            return false;
        }
        dest.setFromBuffer(
                netQty, avgEntry, Double.longBitsToDouble(realizedPnlBits), totalFees, leavesBuy, leavesSell);
        return true;
    }

    /**
     * Spins until a consistent read is obtained. Zero allocation.
     */
    public void readSpinning(int slot, Position dest) {
        while (!read(slot, dest)) {
            // spin until writer completes
        }
    }
}
