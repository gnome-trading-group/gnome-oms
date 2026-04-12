package group.gnometrading.oms.pnl;

import group.gnometrading.utils.ByteBufferUtils;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Shared off-heap buffer for zero-GC cross-thread mark price reads.
 *
 * <p>Each slot is exactly 64 bytes (one cache line) to eliminate false sharing.
 * The {@link PriceWriterAgent} writes trade prices via a seqlock; readers spin
 * until a consistent snapshot is obtained.
 *
 * <p>Slot layout:
 *
 * <pre>
 *   [0]  version    (long) — odd = writer active, even = stable
 *   [8]  markPrice  (long) — last trade price
 *   [16] padding    (48 bytes)
 * </pre>
 */
public final class SharedPriceBuffer {

    private static final int SLOT_SIZE = 64;
    private static final int VERSION_OFFSET = 0;
    private static final int MARK_PRICE_OFFSET = 8;

    private final UnsafeBuffer buffer;
    private final int maxSlots;
    private int nextSlot = 0;

    public SharedPriceBuffer(int maxSlots) {
        this.maxSlots = maxSlots;
        this.buffer = ByteBufferUtils.createAlignedUnsafeBuffer(maxSlots * SLOT_SIZE);
    }

    public int capacity() {
        return maxSlots;
    }

    /**
     * Assigns and returns the next dense slot index. Call at startup only.
     */
    public int register() {
        return nextSlot++;
    }

    /**
     * Writes a mark price with seqlock ordering. Must only be called from the writer thread.
     */
    public void write(int slot, long markPrice) {
        int base = slot * SLOT_SIZE;
        long version = buffer.getLong(base + VERSION_OFFSET);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 1);
        buffer.putLong(base + MARK_PRICE_OFFSET, markPrice);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 2);
    }

    /**
     * Attempts a single seqlock read.
     *
     * @return the mark price if the read was consistent, or {@code Long.MIN_VALUE} if a writer
     *     was active or a torn read occurred
     */
    public long read(int slot) {
        int base = slot * SLOT_SIZE;
        long v1 = buffer.getLongVolatile(base + VERSION_OFFSET);
        if ((v1 & 1) != 0) {
            return Long.MIN_VALUE;
        }
        long markPrice = buffer.getLong(base + MARK_PRICE_OFFSET);
        long v2 = buffer.getLongVolatile(base + VERSION_OFFSET);
        if (v1 != v2) {
            return Long.MIN_VALUE;
        }
        return markPrice;
    }

    /**
     * Spins until a consistent read is obtained. Zero allocation.
     */
    public long readSpinning(int slot) {
        long price;
        while ((price = read(slot)) == Long.MIN_VALUE) {
            // spin until writer completes
        }
        return price;
    }
}
