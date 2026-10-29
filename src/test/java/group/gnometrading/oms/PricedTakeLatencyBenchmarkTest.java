package group.gnometrading.oms;

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
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A market take's latency from the strategy sending it to the order leaving the OMS, while market data flows, with
 * the price writer on a thread of its own (as sessions ran it until 2026-10-29) and polled by the OMS (as they run it
 * now). Threads as in a session: feed, strategy, OMS, venue. Asks rise with every book, so a take priced below the ask
 * the strategy saw was priced off an older book ("stale"). Prints numbers rather than asserting; timings vary by
 * machine, so compare the modes within one run.
 */
@Tag("benchmark")
class PricedTakeLatencyBenchmarkTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 2;
    private static final int LISTING_ID = 6;
    private static final int STREAM_ID = 11;
    private static final int STRATEGY_ID = 5;
    private static final long TICK = Statics.PRICE_SCALING_FACTOR / 100;
    private static final long BASE_ASK = 50 * Statics.PRICE_SCALING_FACTOR;
    private static final long TAKE_SIZE = Statics.SIZE_SCALING_FACTOR;
    private static final long MIN_INTENT_GAP_NANOS = TimeUnit.MICROSECONDS.toNanos(500);
    private static final long RUN_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final long WARMUP_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_INTENTS = 50_000;

    private record Load(String name, int booksPerSecond, int burstSize, long burstEveryNanos) {}

    @Test
    void compareThePriceWriterOnItsOwnThreadWithTheOmsPollingIt() throws Exception {
        final Load[] loads = {
            new Load("quiet 200/s", 200, 0, 0),
            new Load("busy 20k/s", 20_000, 0, 0),
            new Load("bursts 200/s + 1000 every 100ms", 200, 1_000, TimeUnit.MILLISECONDS.toNanos(100)),
        };
        final StringBuilder out = new StringBuilder("\n#### priced-take latency, strategy send -> order out (us)\n");
        for (final boolean spin : new boolean[] {true, false}) {
            for (final Load load : loads) {
                for (final boolean omsPolls : new boolean[] {false, true}) {
                    out.append(String.format(
                            "%-8s %-34s %-22s %s%n",
                            spin ? "spin" : "backoff",
                            load.name(),
                            omsPolls ? "OMS polls writer" : "writer own thread",
                            run(load, spin, omsPolls)));
                }
            }
        }
        System.out.println(out);
    }

    private String run(final Load load, final boolean spin, final boolean omsPolls) throws Exception {
        final SharedPriceBuffer prices = new SharedPriceBuffer(1);
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        slots.register(LISTING_ID);
        final Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "HYPERLIQUID", "Hyperliquid", "ap-northeast-1"),
                new Security(SECURITY_ID, "BTC", null, null, null, null, null, null, false, false, 0L, 0L, false, 0),
                "BTC",
                "BTC");
        final StreamDirectory streams = new StreamDirectory();
        streams.add(
                new Stream(STREAM_ID, 1, "HYPERLIQUID", "LISTING", LISTING_ID, null, "book", "global", 0, 0L, 0L, true),
                listing);
        final SecurityMaster securityMaster = mock(SecurityMaster.class);
        when(securityMaster.getListing(EXCHANGE_ID, SECURITY_ID)).thenReturn(listing);
        when(securityMaster.getListing(LISTING_ID)).thenReturn(listing);
        when(securityMaster.getListingSpec(eq(LISTING_ID), anyLong()))
                .thenReturn(new ListingSpec(LISTING_ID, TICK, 0, 0, 1, 0));

        final GlobalSequence sequence = new GlobalSequence();
        final SequencedRingBuffer<Mbp10Schema> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence);
        final SequencedRingBuffer<Intent> intents = new SequencedRingBuffer<>(Intent::new, sequence);
        final SequencedRingBuffer<OrderExecutionReport> venueReports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
        final SequencedRingBuffer<Order> outbound = new SequencedRingBuffer<>(Order::new, sequence);
        final SequencedRingBuffer<OrderExecutionReport> strategyReports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);

        final OrderManagementSystem oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(256),
                new DefaultPositionTracker(new SharedPositionBuffer(16)),
                new RiskEngine(),
                securityMaster,
                prices,
                slots,
                LedgerSink.NONE,
                System::nanoTime);
        final PriceWriter writer = new PriceWriter(prices, slots, streams, marketData);
        // The old wiring: the OMS's own writer reads an empty buffer while the real one runs on its own thread.
        final PriceWriter omsWriter = omsPolls
                ? writer
                : new PriceWriter(
                        prices, slots, streams, new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence()));
        final OmsAgent omsAgent =
                new OmsAgent(oms, intents, venueReports, outbound, strategyReports, omsWriter, System::nanoTime);

        final long[] sentAt = new long[MAX_INTENTS];
        final long[] askSeen = new long[MAX_INTENTS];
        final long[] latency = new long[MAX_INTENTS];
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger received = new AtomicInteger();
        final int[] stale = new int[1];
        final AtomicBoolean running = new AtomicBoolean(true);
        final long warmEnds = System.nanoTime() + WARMUP_NANOS;

        final Mbp10Schema seenBook = new Mbp10Schema();
        final SequencedPoller strategyBooks = marketData.createPoller((seq, templateId, buffer, length) -> {
            seenBook.buffer.putBytes(0, buffer, 0, length);
            seenBook.wrap(seenBook.buffer);
            final long now = System.nanoTime();
            final int k = sent.get();
            if (k >= MAX_INTENTS || k > received.get() + 32) {
                return;
            }
            if (k > 0 && now - sentAt[k - 1] < MIN_INTENT_GAP_NANOS) {
                return;
            }
            askSeen[k] = seenBook.decoder.askPrice0();
            final Intent slot = intents.claim();
            slot.encoder
                    .strategyId(STRATEGY_ID)
                    .exchangeId(EXCHANGE_ID)
                    .securityId(SECURITY_ID)
                    .bidPrice(group.gnometrading.schemas.IntentDecoder.bidPriceNullValue())
                    .bidSize(group.gnometrading.schemas.IntentDecoder.bidSizeNullValue())
                    .askPrice(group.gnometrading.schemas.IntentDecoder.askPriceNullValue())
                    .askSize(group.gnometrading.schemas.IntentDecoder.askSizeNullValue())
                    // The intent's index rides in its size, so its order names the intent it came from.
                    .takeSize((k + 1) * TAKE_SIZE)
                    .takeSide(Side.Bid)
                    .takeOrderType(OrderType.MARKET)
                    .takeLimitPrice(0);
            sentAt[k] = System.nanoTime();
            intents.publish();
            sent.incrementAndGet();
        });
        final OrderExecutionReport report = new OrderExecutionReport();
        final int[] refused = new int[1];
        final SequencedPoller strategyFills = strategyReports.createPoller((seq, templateId, buffer, length) -> {
            report.buffer.putBytes(0, buffer, 0, length);
            report.wrap(report.buffer);
            if (report.decoder.execType() == ExecType.REJECT) {
                refused[0]++;
                received.incrementAndGet();
            }
        });

        final Order order = new Order();
        final SequencedPoller venue = outbound.createPoller((seq, templateId, buffer, length) -> {
            if (templateId != OrderDecoder.TEMPLATE_ID) {
                return;
            }
            final long now = System.nanoTime();
            order.buffer.putBytes(0, buffer, 0, length);
            order.wrap(order.buffer);
            final int k = (int) (order.decoder.size() / TAKE_SIZE) - 1;
            if (sentAt[k] >= warmEnds) {
                latency[k] = now - sentAt[k];
                if (order.decoder.price() < askSeen[k]) {
                    stale[0]++;
                }
            } else {
                latency[k] = -1;
            }
            final OrderExecutionReport fill = venueReports.claim();
            fill.encodeClientOid(order.getClientOidCounter(), order.getClientOidStrategyId());
            fill.encoder
                    .exchangeId(EXCHANGE_ID)
                    .securityId(SECURITY_ID)
                    .execType(ExecType.FILL)
                    .orderStatus(OrderStatus.NULL_VAL)
                    .filledQty(order.decoder.size())
                    .fillPrice(order.decoder.price())
                    .cumulativeQty(order.decoder.size())
                    .leavesQty(0)
                    .timestampEvent(now)
                    .timestampRecv(now)
                    .fee(0);
            fill.encoder.flags().clear();
            venueReports.publish();
            received.incrementAndGet();
        });

        final Thread omsThread =
                loop("oms", running, spin ? NoOpIdleStrategy.INSTANCE : new BackoffIdleStrategy(), () -> {
                    return omsAgent.doWork();
                });
        final Thread writerThread = omsPolls
                ? null
                : loop("price-writer", running, new SleepingIdleStrategy(1, TimeUnit.MILLISECONDS), writer::poll);
        final Thread strategyThread = loop("strategy", running, NoOpIdleStrategy.INSTANCE, () -> {
            return strategyBooks.poll() + strategyFills.poll();
        });
        final Thread venueThread = loop("venue", running, NoOpIdleStrategy.INSTANCE, venue::poll);

        final long periodNanos = TimeUnit.SECONDS.toNanos(1) / load.booksPerSecond();
        final long start = System.nanoTime();
        long nextBook = start;
        long nextBurst = load.burstSize() > 0 ? start + load.burstEveryNanos() : Long.MAX_VALUE;
        long ask = BASE_ASK;
        while (System.nanoTime() - start < RUN_NANOS) {
            final long now = System.nanoTime();
            if (now >= nextBurst) {
                for (int i = 0; i < load.burstSize(); i++) {
                    ask += TICK;
                    publishBook(marketData, ask);
                }
                nextBurst += load.burstEveryNanos();
            }
            if (now >= nextBook) {
                ask += TICK;
                publishBook(marketData, ask);
                nextBook += periodNanos;
            }
            Thread.onSpinWait();
        }
        running.set(false);
        for (final Thread thread : new Thread[] {omsThread, writerThread, strategyThread, venueThread}) {
            if (thread != null) {
                // Daemon threads: one left waiting on a full ring after the others stop mustn't hold up the run.
                thread.join(TimeUnit.SECONDS.toMillis(5));
            }
        }

        final int count = received.get();
        final long[] measured =
                Arrays.stream(latency, 0, count).filter(l -> l >= 0).sorted().toArray();
        if (measured.length == 0) {
            return "no takes measured";
        }
        return String.format(
                "n=%5d refused %d p50 %7.1f p90 %7.1f p99 %7.1f p99.9 %8.1f max %8.1f  stale %d (%.2f%%)",
                measured.length,
                refused[0],
                us(measured, 0.50),
                us(measured, 0.90),
                us(measured, 0.99),
                us(measured, 0.999),
                measured[measured.length - 1] / 1_000.0,
                stale[0],
                100.0 * stale[0] / measured.length);
    }

    private static double us(final long[] sorted, final double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * quantile))] / 1_000.0;
    }

    private static void publishBook(final SequencedRingBuffer<Mbp10Schema> marketData, final long ask) {
        final Mbp10Schema book = marketData.claim();
        book.encoder
                .streamId(STREAM_ID)
                .action(Action.Modify)
                .bidPrice0(ask - TICK)
                .bidSize0(1_000_000)
                .askPrice0(ask)
                .askSize0(1_000_000);
        marketData.publish();
    }

    private interface Work {
        int doWork() throws Exception;
    }

    private static Thread loop(
            final String name, final AtomicBoolean running, final IdleStrategy idle, final Work work) {
        final Thread thread = new Thread(
                () -> {
                    try {
                        while (running.get()) {
                            idle.idle(work.doWork());
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
