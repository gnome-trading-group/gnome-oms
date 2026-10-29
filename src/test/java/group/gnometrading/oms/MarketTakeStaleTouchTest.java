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
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.OrderState;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
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
 * A market take must be priced off the book the strategy reacted to. The strategy reads a new book and sends a market
 * buy before anything has written that book into the {@link SharedPriceBuffer}; priced off the previous ask, the IOC
 * never reaches the real one and the venue refuses it. The OMS polls the price writer before every intent, so the take
 * goes out at the new ask. Prices are from a recorded Hyperliquid BTC book.
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

    private final List<Long> sentCounters = new ArrayList<>();
    private Runnable onFill = () -> {};
    private PriceWriter priceWriter;
    private OmsAgent omsAgent;
    private SequencedPoller outboundPoller;

    @Test
    void marketTakeIsPricedOffTheBookTheStrategySawBeforeItsIntent() throws Exception {
        buildOms();

        // The book the price writer last wrote.
        publishBook(80_062 * DOLLAR, 80_063 * DOLLAR);
        priceWriter.poll();

        // The book moves, and the strategy sees it and sends a market buy before anything has
        // written that book into the price buffer.
        publishBook(80_064 * DOLLAR, 80_065 * DOLLAR);
        publishMarketBuy();
        omsAgent.doWork();
        outboundPoller.poll();

        assertEquals(1, sentPrices.size());
        assertEquals(80_065 * DOLLAR, sentPrices.get(0), "priced at a touch older than the book the strategy saw");
    }

    @Test
    void marketTakeIsPricedOffABookPublishedWhileTheOmsHandledAnExecReport() throws Exception {
        buildOms();
        publishBook(80_062 * DOLLAR, 80_063 * DOLLAR);
        publishMarketBuy();
        omsAgent.doWork();
        outboundPoller.poll();
        assertEquals(80_063 * DOLLAR, sentPrices.get(0));

        // The strategy runs on its own thread: while the OMS is booking the first take's fill, after the top of its
        // pass, the book moves and the strategy sends another take off it.
        onFill = () -> {
            publishBook(80_064 * DOLLAR, 80_065 * DOLLAR);
            publishMarketBuy();
        };
        publishFill(sentCounters.get(0), 80_063 * DOLLAR);
        omsAgent.doWork();
        outboundPoller.poll();

        assertEquals(2, sentPrices.size(), "the second take went out in the same pass");
        assertEquals(80_065 * DOLLAR, sentPrices.get(1), "priced at a touch older than the book the strategy saw");
    }

    private void buildOms() {
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
                new FillHook(),
                () -> 0L);
        priceWriter = new PriceWriter(priceBuffer, priceSlots, streams, marketData);
        omsAgent = new OmsAgent(oms, intents, execReports, outbound, strategyExecReports, priceWriter, () -> 0L);
        outboundPoller = outbound.createPoller((seq, templateId, buffer, length) -> {
            if (templateId == OrderDecoder.TEMPLATE_ID) {
                Order order = new Order();
                order.buffer.putBytes(0, buffer, 0, length);
                order.wrap(order.buffer);
                assertEquals(TimeInForce.IMMEDIATE_OR_CANCELED, order.decoder.timeInForce());
                sentPrices.add(order.decoder.price());
                sentCounters.add(order.getClientOidCounter());
            }
        });
    }

    private void publishFill(long clientOidCounter, long price) {
        OrderExecutionReport fill = execReports.claim();
        fill.encodeClientOid(clientOidCounter, STRATEGY_ID);
        fill.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(ExecType.FILL)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty(TAKE_SIZE)
                .fillPrice(price)
                .cumulativeQty(TAKE_SIZE)
                .leavesQty(0)
                .rejectReason(RejectReason.NULL_VAL)
                .fee(0);
        fill.encoder.flags().clear();
        execReports.publish();
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

    /** Runs {@link #onFill} when the OMS books a fill, standing in for a strategy thread publishing mid-pass. */
    private final class FillHook implements LedgerSink {
        @Override
        public void orderOpened(
                int strategyId,
                int listingId,
                int exchangeId,
                long clientOidCounter,
                Side side,
                long price,
                long size,
                long timeNs) {}

        @Override
        public void orderAcked(
                int strategyId, int listingId, int exchangeId, long clientOidCounter, OrderExecutionReport report) {}

        @Override
        public void orderModified(
                int strategyId,
                int listingId,
                int exchangeId,
                long clientOidCounter,
                long price,
                long size,
                long timeNs) {}

        @Override
        public void fillBooked(
                int strategyId,
                int listingId,
                long clientOidCounter,
                long cumQtyAfter,
                Side side,
                long qty,
                long price,
                long fee,
                long eventTimeNs,
                Liquidity liquidity,
                Position after) {
            onFill.run();
        }

        @Override
        public void orderClosed(
                int strategyId,
                int listingId,
                int exchangeId,
                long clientOidCounter,
                long filledQty,
                OrderState state,
                RejectReason rejectReason,
                long timeNs) {}

        @Override
        public void orderRefused(int listingId, RejectReason reason) {}

        @Override
        public boolean isFailing(long nowNs) {
            return false;
        }
    }
}
