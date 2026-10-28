package group.gnometrading.oms.pnl;

import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.schemas.MboSchema;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.MboDecoder;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.schemas.book.Mbp1Decoder;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Reads market data from a ring buffer and writes prices to {@link SharedPriceBuffer}.
 *
 * <p>MBP1 and MBP10 messages carry the top of book on every action, so each one writes the best bid and ask;
 * a trade also writes the last trade price. MBO messages carry no book, so only their trades are written.
 *
 * <p>A record flagged bad data (a gap, a resync, a lost feed) writes no prices and flags the listing's slot bad; the
 * next good book record clears it. Marks so hold the last good book while it is bad.
 *
 * <p>Records name only their stream; the session's {@link StreamDirectory} gives the listing whose price slot it is.
 *
 * <p>Run via {@link group.gnometrading.concurrent.GnomeAgentRunner} on its own thread.
 */
public final class PriceWriterAgent implements GnomeAgent, SequencedEventHandler {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final StreamDirectory streams;
    private final SequencedPoller marketDataPoller;

    private final MboSchema mbo = new MboSchema();
    private final Mbp1Schema mbp1 = new Mbp1Schema();
    private final Mbp10Schema mbp10 = new Mbp10Schema();

    public PriceWriterAgent(
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final StreamDirectory streams,
            final SequencedRingBuffer<?> marketDataBuffer) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.streams = streams;
        this.marketDataPoller = marketDataBuffer.createPoller(this);
    }

    @Override
    public void onStart() {
        // disambiguate: GnomeAgent.onStart() and Disruptor EventHandlerBase.onStart()
    }

    @Override
    public int doWork() throws Exception {
        return marketDataPoller.poll();
    }

    @Override
    public void onSequencedEvent(final long globalSeq, final int templateId, final UnsafeBuffer buf, final int len)
            throws Exception {
        if (templateId == MboDecoder.TEMPLATE_ID) {
            mbo.wrap(buf);
            final int slot = slotFor(mbo.streamId());
            // An MBO record carries no whole book, so only a bad one changes trust; a good one can't clear it.
            if (mbo.isBadData()) {
                markBad(slot, mbo.getEventTimestamp());
            } else if (mbo.decoder.action() == Action.Trade) {
                writeTrade(slot, mbo.decoder.price());
            }
        } else if (templateId == Mbp1Decoder.TEMPLATE_ID) {
            mbp1.wrap(buf);
            final int slot = slotFor(mbp1.streamId());
            if (trusted(slot, mbp1.isBadData(), mbp1.getEventTimestamp())) {
                writeQuote(slot, mbp1.decoder.bidPrice0(), mbp1.decoder.askPrice0());
                if (mbp1.decoder.action() == Action.Trade) {
                    writeTrade(slot, mbp1.decoder.price());
                }
            }
        } else if (templateId == Mbp10Decoder.TEMPLATE_ID) {
            mbp10.wrap(buf);
            final int slot = slotFor(mbp10.streamId());
            if (trusted(slot, mbp10.isBadData(), mbp10.getEventTimestamp())) {
                writeQuote(slot, mbp10.decoder.bidPrice0(), mbp10.decoder.askPrice0());
                if (mbp10.decoder.action() == Action.Trade) {
                    writeTrade(slot, mbp10.decoder.price());
                }
            }
        }
    }

    private int slotFor(final int streamId) {
        return priceSlotRegistry.getSlot(streams.listingId(streamId));
    }

    /** Flags or clears the slot from a book record; true when its prices may be written. */
    private boolean trusted(final int slot, final boolean badData, final long eventTimestamp) {
        if (slot == IntToIntHashMap.MISSING) {
            return false;
        }
        if (badData) {
            priceBuffer.markBad(slot, eventTimestamp);
            return false;
        }
        priceBuffer.markGood(slot);
        return true;
    }

    private void markBad(final int slot, final long eventTimestamp) {
        if (slot != IntToIntHashMap.MISSING) {
            priceBuffer.markBad(slot, eventTimestamp);
        }
    }

    private void writeTrade(final int slot, final long price) {
        if (slot == IntToIntHashMap.MISSING || price <= 0) {
            return;
        }
        priceBuffer.writeTrade(slot, price);
    }

    // An empty side arrives as the SBE null value, which is negative; readers treat 0 as unknown.
    private void writeQuote(final int slot, final long bid, final long ask) {
        if (slot == IntToIntHashMap.MISSING) {
            return;
        }
        priceBuffer.writeQuote(slot, Math.max(bid, 0), Math.max(ask, 0));
    }
}
