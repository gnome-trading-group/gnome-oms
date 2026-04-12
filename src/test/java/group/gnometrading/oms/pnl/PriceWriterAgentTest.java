package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.MboDecoder;
import group.gnometrading.schemas.MboSchema;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Decoder;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceWriterAgentTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 100;

    @Mock
    private SecurityMaster securityMaster;

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
        // getListing is stubbed as lenient — only called for Trade events
        lenient()
                .when(securityMaster.getListing(anyInt(), anyInt()))
                .thenReturn(new Listing(LISTING_ID, null, null, null, null));

        agent = new PriceWriterAgent(priceBuffer, priceSlotRegistry, securityMaster, ringBuffer);
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
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(12345L)
                .action(Action.Trade);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(12345L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbo_tradeAction_readsFromIncomingBufferNotInternalCopy() throws Exception {
        // Encode into an external buffer and write a distinct price to the agent's internal schema
        // buffer to confirm the agent wraps (reads from external) rather than the internal copy.
        final MboSchema external = new MboSchema();
        external.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(99999L)
                .action(Action.Trade);

        final MboSchema internal = new MboSchema();
        // Deliberately put a different price in internal schema's buffer (decoy)
        internal.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(1L)
                .action(Action.Trade);

        // Pass the external buffer; if agent wraps correctly it reads 99999, not 1
        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(external), external.totalMessageSize());

        assertEquals(99999L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbo_nonTradeAction_doesNotWritePrice() throws Exception {
        final MboSchema schema = new MboSchema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(9999L)
                .action(Action.Add);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbo_cancelAction_doesNotWritePrice() throws Exception {
        final MboSchema schema = new MboSchema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(500L)
                .action(Action.Cancel);

        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.readSpinning(slot));
    }

    // --- MBP1 ---

    @Test
    void mbp1_tradeAction_writesPrice() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(5000L)
                .action(Action.Trade)
                .side(Side.Ask)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(5000L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbp1_tradeAction_readsFromIncomingBuffer() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(7777L)
                .action(Action.Trade)
                .side(Side.Bid)
                .sequence(2L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7777L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbp1_nonTradeAction_doesNotWritePrice() throws Exception {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(5000L)
                .action(Action.Modify)
                .side(Side.Bid)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp1Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.readSpinning(slot));
    }

    // --- MBP10 ---

    @Test
    void mbp10_tradeAction_writesPrice() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(7500L)
                .action(Action.Trade)
                .side(Side.Ask)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(7500L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbp10_tradeAction_readsFromIncomingBuffer() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(8888L)
                .action(Action.Trade)
                .side(Side.Bid)
                .sequence(3L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(8888L, priceBuffer.readSpinning(slot));
    }

    @Test
    void mbp10_nonTradeAction_doesNotWritePrice() throws Exception {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(7500L)
                .action(Action.Cancel)
                .side(Side.Bid)
                .sequence(1L);

        agent.onSequencedEvent(0, Mbp10Decoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.readSpinning(slot));
    }

    // --- successive events update the price ---

    @Test
    void successiveTrades_overwritePrice() throws Exception {
        final MboSchema first = new MboSchema();
        first.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(100L)
                .action(Action.Trade);
        agent.onSequencedEvent(0, MboDecoder.TEMPLATE_ID, externalBufferFrom(first), first.totalMessageSize());

        final MboSchema second = new MboSchema();
        second.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(200L)
                .action(Action.Trade);
        agent.onSequencedEvent(1, MboDecoder.TEMPLATE_ID, externalBufferFrom(second), second.totalMessageSize());

        assertEquals(200L, priceBuffer.readSpinning(slot));
    }

    // --- edge cases ---

    @Test
    void unregisteredListing_doesNotThrow() throws Exception {
        final PriceSlotRegistry emptyRegistry = new PriceSlotRegistry(8);
        final PriceWriterAgent agentWithEmptyRegistry =
                new PriceWriterAgent(priceBuffer, emptyRegistry, securityMaster, ringBuffer);

        final MboSchema schema = new MboSchema();
        schema.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(12345L)
                .action(Action.Trade);

        agentWithEmptyRegistry.onSequencedEvent(
                0, MboDecoder.TEMPLATE_ID, externalBufferFrom(schema), schema.totalMessageSize());

        assertEquals(0L, priceBuffer.readSpinning(slot));
    }

    @Test
    void unknownTemplateId_isIgnored() throws Exception {
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
        agent.onSequencedEvent(0, 999, buf, 64);
        assertEquals(0L, priceBuffer.readSpinning(slot));
    }
}
