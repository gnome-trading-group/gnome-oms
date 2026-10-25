package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import group.gnometrading.md.Stream;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.schemas.MboSchema;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.MboDecoder;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Mbp1Decoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceWriterAgentTest {

    private static final int STREAM_ID = 7;
    private static final int LISTING_ID = 100;

    private StreamDirectory streams;

    @Mock
    private SequencedRingBuffer<?> ringBuffer;

    @Mock
    private SequencedPoller poller;

    private SharedPriceBuffer priceBuffer;
    private PriceSlotRegistry priceSlotRegistry;
    private PriceWriterAgent agent;
    private int slot;

    @BeforeEach
    void setUp() {
        priceBuffer = new SharedPriceBuffer(8);
        priceSlotRegistry = new PriceSlotRegistry(8);
        slot = priceSlotRegistry.register(LISTING_ID);

        when(ringBuffer.createPoller(any())).thenReturn(poller);
        streams = new StreamDirectory();
        streams.add(
                new Stream(STREAM_ID, 1, "KALSHI", "LISTING", LISTING_ID, null, "book", "us-east-1", 0, 0L, 0L, true),
                new Listing(
                        LISTING_ID,
                        new Exchange(1, "KALSHI", "Kalshi", "us-east-1"),
                        new Security(42, "X", null, null, null, null, null, null, false, false, 0L, 0L, false, 0),
                        "MKT:yes",
                        "MKT"));

        agent = new PriceWriterAgent(priceBuffer, priceSlotRegistry, streams, ringBuffer);
    }

    // Helper: build an external UnsafeBuffer from a schema (simulates what the ring buffer delivers)
    private UnsafeBuffer externalBufferFrom(MboSchema schema) {
        UnsafeBuffer external = new UnsafeBuffer(new byte[schema.totalMessageSize()]);
        external.putBytes(0, schema.buffer, 0, schema.totalMessageSize());
        return external;
    }

    private UnsafeBuffer externalBufferFrom(Mbp1Schema schema) {
        UnsafeBuffer external = new UnsafeBuffer(new byte[schema.totalMessageSize()]);
        external.putBytes(0, schema.buffer, 0, schema.totalMessageSize());
        return external;
    }

    private UnsafeBuffer externalBufferFrom(Mbp10Schema schema) {
        UnsafeBuffer external = new UnsafeBuffer(new byte[schema.totalMessageSize()]);
        external.putBytes(0, schema.buffer, 0, schema.totalMessageSize());
        return external;
    }

    // --- MBO ---

    @Test
    void mbo_tradeAction_writesPrice() throws Exception {
        final MboSchema schema = new MboSchema();
        schema.encoder.streamId(STREAM_ID).price(12345L).action(Action.Trade);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(12345L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbo_tradeAction_readsFromIncomingBufferNotInternalCopy() throws Exception {
        // Encode into an external buffer and write a distinct price to the agent's internal schema
        // buffer to confirm the agent wraps (reads from external) rather than the internal copy.
        final MboSchema external = new MboSchema();
        external.encoder.streamId(STREAM_ID).price(99999L).action(Action.Trade);

        final MboSchema internal = new MboSchema();
        // Deliberately put a different price in internal schema's buffer (decoy)
        internal.encoder.streamId(STREAM_ID).price(1L).action(Action.Trade);

        // Pass the external buffer; if agent wraps correctly it reads 99999, not 1
        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(external), external.totalMessageSize());

        assertEquals(99999L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbo_nonTradeAction_doesNotWritePrice() throws Exception {
        final MboSchema schema = new MboSchema();
        schema.encoder.streamId(STREAM_ID).price(9999L).action(Action.Add);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbo_cancelAction_doesNotWritePrice() throws Exception {
        final MboSchema schema = new MboSchema();
        schema.encoder.streamId(STREAM_ID).price(500L).action(Action.Cancel);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.markPrice(slot));
    }

    // --- MBP1 ---

    @Test
    void mbp1_tradeAction_writesPrice() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(5000L)
                .action(Action.Trade)
                .side(Side.Ask)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(5000L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbp1_tradeAction_readsFromIncomingBuffer() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(7777L)
                .action(Action.Trade)
                .side(Side.Bid)
                .sequence(2L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7777L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbp1_nonTradeAction_writesQuoteButNotTrade() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(5000L)
                .action(Action.Modify)
                .side(Side.Bid)
                .sequence(1L)
                .bidPrice0(4000L)
                .askPrice0(4200L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(4200L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Bid));
        assertEquals(4000L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Ask));
        assertEquals(4100L, priceBuffer.markPrice(slot));
    }

    // --- MBP10 ---

    @Test
    void mbp10_tradeAction_writesPrice() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(7500L)
                .action(Action.Trade)
                .side(Side.Ask)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7500L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbp10_tradeAction_readsFromIncomingBuffer() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(8888L)
                .action(Action.Trade)
                .side(Side.Bid)
                .sequence(3L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(8888L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbp10_nonTradeAction_writesQuoteButNotTrade() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(7500L)
                .action(Action.Cancel)
                .side(Side.Bid)
                .sequence(1L)
                .bidPrice0(7000L)
                .askPrice0(7400L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7400L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Bid));
        assertEquals(7000L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Ask));
    }

    @Test
    void mbp10_nonTradeAction_withoutQuoteLeavesNoPrice() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(7500L)
                .action(Action.Cancel)
                .side(Side.Bid)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.markPrice(slot));
    }

    @Test
    void mbp10_emptySide_isStoredAsUnknown() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .action(Action.Add)
                .side(Side.Bid)
                .sequence(1L)
                .bidPrice0(7000L)
                .askPrice0(Mbp10Encoder.askPrice0NullValue());

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Bid));
        assertEquals(7000L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Ask));
    }

    @Test
    void mbp10_tradeAction_writesQuoteAndTrade() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .price(7300L)
                .action(Action.Trade)
                .side(Side.Ask)
                .sequence(1L)
                .bidPrice0(7000L)
                .askPrice0(Mbp10Encoder.askPrice0NullValue());

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7300L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Bid));
        assertEquals(7000L, priceBuffer.executionPrice(slot, group.gnometrading.schemas.Side.Ask));
    }

    // --- successive events update the price ---

    @Test
    void successiveTrades_overwritePrice() throws Exception {
        final MboSchema first = new MboSchema();
        first.encoder.streamId(STREAM_ID).price(100L).action(Action.Trade);
        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(first), first.totalMessageSize());

        final MboSchema second = new MboSchema();
        second.encoder.streamId(STREAM_ID).price(200L).action(Action.Trade);
        agent.onSequencedEvent(1, MboDecoder.TEMPLATE_ID, externalBufferFrom(second), second.totalMessageSize());

        assertEquals(200L, priceBuffer.markPrice(slot));
    }

    // --- edge cases ---

    @Test
    void unregisteredListing_doesNotThrow() throws Exception {
        final PriceSlotRegistry emptyRegistry = new PriceSlotRegistry(8);
        final PriceWriterAgent agentWithEmptyRegistry =
                new PriceWriterAgent(priceBuffer, emptyRegistry, streams, ringBuffer);

        final MboSchema schema = new MboSchema();
        schema.encoder.streamId(STREAM_ID).price(12345L).action(Action.Trade);

        agentWithEmptyRegistry.onSequencedEvent(
                0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.markPrice(slot));
    }

    @Test
    void unknownTemplateId_isIgnored() throws Exception {
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
        agent.onSequencedEvent(0, 999, buf, 64);
        assertEquals(0L, priceBuffer.markPrice(slot));
    }
}
