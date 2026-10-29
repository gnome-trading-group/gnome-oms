package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.Stream;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.PriceWriter;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.MboSchema;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The price writer runs inside {@link OmsAgent#doWork}, so every market data record the strategy gets passes through
 * the OMS hot path: MBO, MBP1 and MBP10, good or flagged bad data, must not allocate once warm.
 */
@Tag("allocation")
class OmsMarketDataAllocationTest {

    private static final int STREAM_ID = 11;
    private static final int CYCLES = 200_000;
    private static final int RECORDS_PER_CYCLE = 8;
    private static final long PRICE = Statics.PRICE_SCALING_FACTOR / 2;
    private static final long TICK = Statics.PRICE_SCALING_FACTOR / 100;
    // Room for the measurement itself; a single allocation per cycle would be megabytes.
    private static final long ALLOWED_BYTES = 16 * 1024;

    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void everyBookSchemaGoodOrBadAllocatesNothingThroughTheOmsPass() throws Exception {
        final SharedPriceBuffer prices = new SharedPriceBuffer(1);
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        final int slot = slots.register(OmsTestHarness.LISTING_ID);
        final StreamDirectory streams = new StreamDirectory();
        streams.add(
                new Stream(
                        STREAM_ID,
                        1,
                        "KALSHI",
                        "LISTING",
                        OmsTestHarness.LISTING_ID,
                        null,
                        "book",
                        "us-east-1",
                        0,
                        0L,
                        0L,
                        true),
                new Listing(
                        OmsTestHarness.LISTING_ID,
                        new Exchange(OmsTestHarness.EXCHANGE_ID, "KALSHI", "Kalshi", "us-east-1"),
                        new Security(
                                OmsTestHarness.SECURITY_ID,
                                "S",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                false,
                                0L,
                                0L,
                                false,
                                0),
                        "S",
                        "S"));

        final GlobalSequence sequence = new GlobalSequence();
        final SequencedRingBuffer<Mbp10Schema> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence);
        final OrderManagementSystem oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(16)),
                new RiskEngine(),
                // Never queried: no intents. Not the harness's mocked registry, because a Mockito inline mock
                // instruments Object.equals, which the ring's single-producer assertion calls on every publish.
                new SecurityMaster(new RegistryConnection("http://127.0.0.1:1", "unused")),
                prices,
                slots,
                LedgerSink.NONE,
                () -> 1L);
        final OmsAgent agent = new OmsAgent(
                oms,
                new SequencedRingBuffer<>(Intent::new, sequence),
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence),
                new SequencedRingBuffer<>(Order::new, sequence),
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence),
                new PriceWriter(prices, slots, streams, marketData),
                () -> 1L);

        final MboSchema mboTrade = mbo(false);
        final MboSchema mboBad = mbo(true);
        final Mbp1Schema mbp1Book = mbp1(Action.Modify, false);
        final Mbp1Schema mbp1Bad = mbp1(Action.Modify, true);
        final Mbp1Schema mbp1Trade = mbp1(Action.Trade, false);
        final Mbp10Schema mbp10Bad = mbp10(Action.Modify, true);
        final Mbp10Schema mbp10Trade = mbp10(Action.Trade, false);
        final Mbp10Schema mbp10Book = mbp10(Action.Modify, false);
        final long[] written = {0};

        // Each schema in turn, with bad spells opened by MBP1, MBP10 and MBO records and closed by a good book.
        final ThrowingRunnable cycle = () -> {
            marketData.publishRaw(mboTrade.buffer, mboTrade.templateId(), mboTrade.totalMessageSize());
            marketData.publishRaw(mbp1Book.buffer, mbp1Book.templateId(), mbp1Book.totalMessageSize());
            marketData.publishRaw(mbp1Bad.buffer, mbp1Bad.templateId(), mbp1Bad.totalMessageSize());
            marketData.publishRaw(mbp1Trade.buffer, mbp1Trade.templateId(), mbp1Trade.totalMessageSize());
            marketData.publishRaw(mbp10Bad.buffer, mbp10Bad.templateId(), mbp10Bad.totalMessageSize());
            marketData.publishRaw(mboBad.buffer, mboBad.templateId(), mboBad.totalMessageSize());
            marketData.publishRaw(mbp10Trade.buffer, mbp10Trade.templateId(), mbp10Trade.totalMessageSize());
            marketData.publishRaw(mbp10Book.buffer, mbp10Book.templateId(), mbp10Book.totalMessageSize());
            written[0] += agent.doWork();
        };
        allocatedBy(cycle);
        written[0] = 0;
        final long allocated = allocatedBy(cycle);

        assertTrue(allocated < ALLOWED_BYTES, "market data through the OMS pass: " + allocated + " bytes");
        assertEquals((long) CYCLES * RECORDS_PER_CYCLE, written[0], "every record reached the price writer");
        assertEquals(0, prices.badSince(slot), "the last good book cleared the bad spell");
    }

    private static MboSchema mbo(final boolean badData) {
        final MboSchema schema = new MboSchema();
        schema.encoder.streamId(STREAM_ID).timestampEvent(1_000L).price(PRICE).action(Action.Trade);
        schema.encoder.flags().clear().badData(badData);
        return schema;
    }

    private static Mbp1Schema mbp1(final Action action, final boolean badData) {
        final Mbp1Schema schema = new Mbp1Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .timestampEvent(2_000L)
                .price(PRICE)
                .action(action)
                .bidPrice0(PRICE - TICK)
                .askPrice0(PRICE + TICK);
        schema.encoder.flags().clear().badData(badData);
        return schema;
    }

    private static Mbp10Schema mbp10(final Action action, final boolean badData) {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.encoder
                .streamId(STREAM_ID)
                .timestampEvent(3_000L)
                .price(PRICE)
                .action(action)
                .bidPrice0(PRICE - TICK)
                .askPrice0(PRICE + TICK);
        schema.encoder.flags().clear().badData(badData);
        return schema;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private long allocatedBy(final ThrowingRunnable cycle) throws Exception {
        final long threadId = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < CYCLES; i++) {
            cycle.run();
        }
        return threads.getThreadAllocatedBytes(threadId) - before;
    }
}
