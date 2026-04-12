package group.gnometrading.oms.pnl;

import group.gnometrading.SecurityMaster;
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
 * Reads market data from a ring buffer and writes last trade prices to {@link SharedPriceBuffer}.
 *
 * <p>Handles MBO, MBP1, and MBP10 schemas. For each, when {@code action == Trade}, the
 * trade price is written as the mark price for that listing. Other actions are ignored.
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
                writePrice(mbo.decoder.exchangeId(), (int) mbo.decoder.securityId(), mbo.decoder.price());
            }
        } else if (templateId == Mbp1Decoder.TEMPLATE_ID) {
            mbp1.wrap(buf);
            if (mbp1.decoder.action() == Action.Trade) {
                writePrice(mbp1.decoder.exchangeId(), (int) mbp1.decoder.securityId(), mbp1.decoder.price());
            }
        } else if (templateId == Mbp10Decoder.TEMPLATE_ID) {
            mbp10.wrap(buf);
            if (mbp10.decoder.action() == Action.Trade) {
                writePrice(mbp10.decoder.exchangeId(), (int) mbp10.decoder.securityId(), mbp10.decoder.price());
            }
        }
    }

    private void writePrice(final int exchangeId, final int securityId, final long price) {
        if (price <= 0) {
            return;
        }
        int listingId = securityMaster.getListing(exchangeId, securityId).listingId();
        int slot = priceSlotRegistry.getSlot(listingId);
        if (slot == group.gnometrading.collections.IntToIntHashMap.MISSING) {
            return;
        }
        priceBuffer.write(slot, price);
    }
}
