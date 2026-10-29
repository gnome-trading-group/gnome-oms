package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reproduces paper session f1cb2ef8 (2026-10-29), order 41: a market take priced at a touch the strategy had already
 * seen move. The strategy reacted to a new book (journal seq 369, ask 80065) and sent a market buy (seq 371); the OMS
 * priced the IOC at the previous ask, 80063, because {@link PriceWriter} runs on its own background thread
 * (1ms sleeping idle) and had not yet written seq 369 into the {@link SharedPriceBuffer}. With no slippage the IOC
 * never reaches the real ask and the venue (here the paper exchange) refuses it. Orders 96, 365 and 620 are the same
 * race (4 of 53 takes sent right after a book that moved their touch).
 *
 * <p>The OMS now drains the price writer on its own thread before every intent poll, so a take is priced off all
 * market data sequenced before the intent, which is what the strategy decided on.
 */
class MarketTakeStaleTouchTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 2;
    private static final int LISTING_ID = 6;
    private static final int STREAM_ID = 11;
    private static final int STRATEGY_ID = 5;
    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long TAKE_SIZE = 130;

    private final GlobalSequence sequence = new GlobalSequence();
    private final SequencedRingBuffer<Mbp10Schema> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence);
    private final SequencedRingBuffer<Intent> intents = new SequencedRingBuffer<>(Intent::new, sequence);
    private final SequencedRingBuffer<OrderExecutionReport> execReports =
            new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
    private final SequencedRingBuffer<Order> outbound = new SequencedRingBuffer<>(Order::new, sequence, 64);
    private final SequencedRingBuffer<OrderExecutionReport> strategyExecReports =
            new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, 64);
    private final List<Long> sentPrices = new ArrayList<>();

    @Test
    void marketTakeIsPricedOffTheBookTheStrategySawBeforeItsIntent() throws Exception {
        SharedPriceBuffer priceBuffer = new SharedPriceBuffer(1);
        PriceSlotRegistry priceSlots = new PriceSlotRegistry(1);
        priceSlots.register(LISTING_ID);
        Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "HYPERLIQUID", "Hyperliquid", "ap-northeast-1"),
                new Security(SECURITY_ID, "BTC", null, null, null, null, null, null, false, false, 0L, 0L, false, 0),
                "BTC",
                "BTC");
        StreamDirectory streams = new StreamDirectory();
        streams.add(
                new Stream(
                        STREAM_ID,
                        1,
                        "HYPERLIQUID",
                        "LISTING",
                        LISTING_ID,
                        null,
                        "book",
                        "ap-northeast-1",
                        0,
                        0L,
                        0L,
                        true),
                listing);
        SecurityMaster securityMaster = mock(SecurityMaster.class);
        when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
        when(securityMaster.getListing(LISTING_ID)).thenReturn(listing);
        when(securityMaster.getListingSpec(eq(LISTING_ID), anyLong()))
                .thenReturn(new ListingSpec(LISTING_ID, DOLLAR, 0, 0, 1, 0));

        OrderManagementSystem oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(16)),
                new RiskEngine(),
                securityMaster,
                priceBuffer,
                priceSlots,
                LedgerSink.NONE,
                () -> 0L);
        PriceWriter priceWriter = new PriceWriter(priceBuffer, priceSlots, streams, marketData);
        OmsAgent omsAgent =
                new OmsAgent(oms, intents, execReports, outbound, strategyExecReports, priceWriter, () -> 0L);
        SequencedPoller outboundPoller = outbound.createPoller((seq, templateId, buffer, length) -> {
            if (templateId == OrderDecoder.TEMPLATE_ID) {
                Order order = new Order();
                order.buffer.putBytes(0, buffer, 0, length);
                order.wrap(order.buffer);
                assertEquals(TimeInForce.IMMEDIATE_OR_CANCELED, order.decoder.timeInForce());
                sentPrices.add(order.decoder.price());
            }
        });

        // Journal seq 334: the book the price writer last wrote.
        publishBook(80_062 * DOLLAR, 80_063 * DOLLAR);
        priceWriter.poll();

        // Journal seq 369 then 371: the book moves, the strategy sees it and sends a market buy before anything has
        // written that book into the price buffer.
        publishBook(80_064 * DOLLAR, 80_065 * DOLLAR);
        publishMarketBuy();
        omsAgent.doWork();
        outboundPoller.poll();

        assertEquals(1, sentPrices.size());
        assertEquals(80_065 * DOLLAR, sentPrices.get(0), "priced at a touch older than the book the strategy saw");
    }

    private void publishBook(long bid, long ask) {
        Mbp10Schema slot = marketData.claim();
        slot.encoder
                .streamId(STREAM_ID)
                .action(Action.Modify)
                .bidPrice0(bid)
                .bidSize0(1_000_000)
                .askPrice0(ask)
                .askSize0(1_000_000);
        marketData.publish();
    }

    private void publishMarketBuy() {
        Intent intent = OmsTestHarness.buildTakeIntent(
                STRATEGY_ID, EXCHANGE_ID, SECURITY_ID, TAKE_SIZE, Side.Bid, OrderType.MARKET, 0);
        Intent slot = intents.claim();
        slot.buffer.putBytes(0, intent.buffer, 0, intent.totalMessageSize());
        slot.wrap(slot.buffer);
        intents.publish();
    }
}
