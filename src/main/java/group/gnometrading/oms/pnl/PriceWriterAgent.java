package group.gnometrading.oms.pnl;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.MboDecoder;
import group.gnometrading.schemas.MboSchema;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Decoder;
import group.gnometrading.schemas.Mbp1Schema;
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
 * <p>Run via {@link group.gnometrading.concurrent.GnomeAgentRunner} on its own thread.
 */
public final class PriceWriterAgent implements GnomeAgent, SequencedEventHandler {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final SecurityMaster securityMaster;
    private final SequencedPoller marketDataPoller;

    private final MboSchema mbo = new MboSchema();
    private final Mbp1Schema mbp1 = new Mbp1Schema();
    private final Mbp10Schema mbp10 = new Mbp10Schema();

    public PriceWriterAgent(
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final SecurityMaster securityMaster,
            final SequencedRingBuffer<?> marketDataBuffer) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.securityMaster = securityMaster;
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
            if (mbo.decoder.action() == Action.Trade) {
                final int slot = slotFor(mbo.decoder.exchangeId(), (int) mbo.decoder.securityId());
                writeTrade(slot, mbo.decoder.price());
            }
        } else if (templateId == Mbp1Decoder.TEMPLATE_ID) {
            mbp1.wrap(buf);
            final int slot = slotFor(mbp1.decoder.exchangeId(), (int) mbp1.decoder.securityId());
            writeQuote(slot, mbp1.decoder.bidPrice0(), mbp1.decoder.askPrice0());
            if (mbp1.decoder.action() == Action.Trade) {
                writeTrade(slot, mbp1.decoder.price());
            }
        } else if (templateId == Mbp10Decoder.TEMPLATE_ID) {
            mbp10.wrap(buf);
            final int slot = slotFor(mbp10.decoder.exchangeId(), (int) mbp10.decoder.securityId());
            writeQuote(slot, mbp10.decoder.bidPrice0(), mbp10.decoder.askPrice0());
            if (mbp10.decoder.action() == Action.Trade) {
                writeTrade(slot, mbp10.decoder.price());
            }
        }
    }

    private int slotFor(final int exchangeId, final int securityId) {
        return priceSlotRegistry.getSlot(
                securityMaster.getListing(exchangeId, securityId).listingId());
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
