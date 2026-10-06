package group.gnometrading.oms.pnl;

import group.gnometrading.schemas.Side;
import group.gnometrading.utils.ByteBufferUtils;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Shared off-heap buffer for zero-GC cross-thread price reads.
 *
 * <p>Each slot is exactly 64 bytes (one cache line) to eliminate false sharing.
 * The {@link PriceWriterAgent} writes the top of book and the last trade via a seqlock; readers spin
 * until a consistent snapshot is obtained. A price of 0 means unknown: no trade yet, or that side of the
 * book is empty.
 *
 * <p>Prediction markets trade sparsely, so the last trade is often stale or missing while the book is live.
 * Readers therefore prefer the book and fall back to the last trade only when the side they need is empty.
 *
 * <p>Slot layout:
 *
 * <pre>
 *   [0]  version    (long) — odd = writer active, even = stable
 *   [8]  lastTrade  (long)
 *   [16] bid        (long) — best bid
 *   [24] ask        (long) — best ask
 *   [32] padding    (32 bytes)
 * </pre>
 *
 * <p>A buffer-wide price epoch advances after every write that changes a price, so a reader can tell in one load
 * whether any mark has moved, then compare {@link #slotVersion} per slot to find which.
 */
public final class SharedPriceBuffer {

    private static final int SLOT_SIZE = 64;
    private static final int VERSION_OFFSET = 0;
    private static final int LAST_TRADE_OFFSET = 8;
    private static final int BID_OFFSET = 16;
    private static final int ASK_OFFSET = 24;

    private final UnsafeBuffer buffer;
    private final int maxSlots;
    private final int epochOffset;
    private int nextSlot = 0;
    private long epoch;

    public SharedPriceBuffer(int maxSlots) {
        this.maxSlots = maxSlots;
        // The epoch is written on every price change; an empty line between it and the last slot keeps the
        // adjacent-line prefetcher from pulling slot data along with it.
        this.epochOffset = (maxSlots + 1) * SLOT_SIZE;
        this.buffer = ByteBufferUtils.createAlignedUnsafeBuffer(epochOffset + SLOT_SIZE);
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
     * Writes a last trade price with seqlock ordering. Must only be called from the writer thread.
     */
    public void writeTrade(int slot, long price) {
        int base = slot * SLOT_SIZE;
        if (buffer.getLong(base + LAST_TRADE_OFFSET) == price) {
            return;
        }
        long version = buffer.getLong(base + VERSION_OFFSET);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 1);
        buffer.putLong(base + LAST_TRADE_OFFSET, price);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 2);
        buffer.putLongRelease(epochOffset, ++epoch);
    }

    /**
     * Writes the best bid and ask with seqlock ordering; pass 0 for an empty side. Must only be called from the
     * writer thread.
     */
    public void writeQuote(int slot, long bid, long ask) {
        int base = slot * SLOT_SIZE;
        // Depth-only updates repeat the top of book; skipping them spares readers a mark that did not move.
        if (buffer.getLong(base + BID_OFFSET) == bid && buffer.getLong(base + ASK_OFFSET) == ask) {
            return;
        }
        long version = buffer.getLong(base + VERSION_OFFSET);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 1);
        buffer.putLong(base + BID_OFFSET, bid);
        buffer.putLong(base + ASK_OFFSET, ask);
        buffer.putLongVolatile(base + VERSION_OFFSET, version + 2);
        buffer.putLongRelease(epochOffset, ++epoch);
    }

    /** Advances after every write that changes any slot's prices. */
    public long priceEpoch() {
        return buffer.getLongVolatile(epochOffset);
    }

    /** Changes whenever this slot's prices change. */
    public long slotVersion(int slot) {
        return buffer.getLongVolatile(slot * SLOT_SIZE + VERSION_OFFSET);
    }

    /**
     * The price an order on {@code side} would execute at: the best ask for a buy, the best bid for a sell, or the
     * last trade when that side of the book is empty. Returns 0 when none is known. Zero allocation.
     */
    public long executionPrice(int slot, Side side) {
        int base = slot * SLOT_SIZE;
        int sideOffset = side == Side.Bid ? ASK_OFFSET : BID_OFFSET;
        while (true) {
            long v1 = buffer.getLongVolatile(base + VERSION_OFFSET);
            if ((v1 & 1) != 0) {
                continue;
            }
            long sidePrice = buffer.getLong(base + sideOffset);
            long lastTrade = buffer.getLong(base + LAST_TRADE_OFFSET);
            if (buffer.getLongVolatile(base + VERSION_OFFSET) == v1) {
                return sidePrice > 0 ? sidePrice : lastTrade;
            }
        }
    }

    /**
     * Reads the best bid, best ask and last trade, consistent with each other, into {@code dest[0..2]}. Zero
     * allocation.
     */
    public void readTop(int slot, long[] dest) {
        int base = slot * SLOT_SIZE;
        while (true) {
            long v1 = buffer.getLongVolatile(base + VERSION_OFFSET);
            if ((v1 & 1) != 0) {
                continue;
            }
            long bid = buffer.getLong(base + BID_OFFSET);
            long ask = buffer.getLong(base + ASK_OFFSET);
            long lastTrade = buffer.getLong(base + LAST_TRADE_OFFSET);
            if (buffer.getLongVolatile(base + VERSION_OFFSET) == v1) {
                dest[0] = bid;
                dest[1] = ask;
                dest[2] = lastTrade;
                return;
            }
        }
    }

    /**
     * The mid of the best bid and ask, or the last trade when either side is empty. Returns 0 when none is known.
     * Zero allocation.
     */
    public long markPrice(int slot) {
        int base = slot * SLOT_SIZE;
        while (true) {
            long v1 = buffer.getLongVolatile(base + VERSION_OFFSET);
            if ((v1 & 1) != 0) {
                continue;
            }
            long bid = buffer.getLong(base + BID_OFFSET);
            long ask = buffer.getLong(base + ASK_OFFSET);
            long lastTrade = buffer.getLong(base + LAST_TRADE_OFFSET);
            if (buffer.getLongVolatile(base + VERSION_OFFSET) == v1) {
                return bid > 0 && ask > 0 ? bid + (ask - bid) / 2 : lastTrade;
            }
        }
    }
}
