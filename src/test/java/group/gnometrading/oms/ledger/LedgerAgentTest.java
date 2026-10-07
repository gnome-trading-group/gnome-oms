package group.gnometrading.oms.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderState;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerAgentTest {

    private static final long FLUSH_MS = 250;
    private static final long MARK_MS = 1_000;
    private static final long MAX_LAG_NS = 10_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final long[] now = {1_000_000L};
    private final List<String> posted = new ArrayList<>();
    private final Deque<Integer> statuses = new ArrayDeque<>();
    private LedgerRing ring;
    private SharedPriceBuffer prices;
    private LedgerAgent agent;

    @BeforeEach
    void setUp() {
        ring = new LedgerRing(
                64,
                LedgerAgent.MAX_EVENTS_PER_BATCH,
                MAX_LAG_NS,
                new DefaultPositionTracker(new SharedPositionBuffer(8)));
        prices = new SharedPriceBuffer(1);
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        slots.register(100);
        final RegistryConnection registry = mock(RegistryConnection.class);
        when(registry.tryPost(any(), any(byte[].class), anyInt())).thenAnswer(call -> {
            final byte[] body = call.getArgument(1);
            final int length = call.getArgument(2);
            posted.add(new String(body, 0, length, StandardCharsets.UTF_8));
            return statuses.isEmpty() ? 200 : statuses.poll();
        });
        agent = new LedgerAgent(
                ring, registry, () -> now[0], new NullLogger(), "session-1", prices, slots, FLUSH_MS, MARK_MS);
    }

    @Test
    void postsEachKindOfEventAndConfirmsTheBatch() throws Exception {
        final Position after = new Position();
        after.init(100);
        after.applyFill(Side.Bid, 4_000_000, 500_000_000L, 1_000_000);
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 500_000_000L, 10_000_000, 1L);
        final OrderExecutionReport ack = new OrderExecutionReport();
        ack.encoder.exchangeOrderId("0x" + "ab".repeat(32)).timestampRecv(2L);
        ring.orderAcked(7, 100, 1, 3L, ack);
        ring.fillBooked(
                7, 100, 3L, 4_000_000, Side.Bid, 4_000_000, 500_000_000L, 1_000_000, 3L, Liquidity.MAKER, after);
        ring.orderClosed(7, 100, 1, 3L, 4_000_000, OrderState.FILLED, null, 4L);

        agent.doWork();

        assertEquals(1, posted.size());
        final JsonNode batch = mapper.readTree(posted.get(0));
        assertEquals("session-1", batch.get("sessionId").asText());
        final JsonNode fill = batch.get("fills").get(0);
        assertEquals("VENUE", fill.get("source").asText());
        assertEquals(4_000_000, fill.get("cumQtyAfter").asLong());
        assertEquals(0, fill.get("side").asInt(), "a buy");
        assertEquals(2_000_000_000L, fill.get("totalCostAfter").asLong());
        assertEquals(1, fill.get("positionVersion").asLong());
        assertEquals("MAKER", fill.get("liquidity").asText());
        assertEquals(10_000_000, batch.get("orderOpens").get(0).get("size").asLong());
        assertEquals(
                "0x" + "ab".repeat(32),
                batch.get("orderAcks").get(0).get("exchangeOrderId").asText());
        final JsonNode close = batch.get("orderCloses").get(0);
        assertEquals(4_000_000, close.get("filledQty").asLong());
        assertEquals("FILLED", close.get("state").asText());
        assertTrue(close.get("rejectReason").isNull());
        assertFalse(ring.isFailing(MAX_LAG_NS * 2), "every event confirmed");
    }

    @Test
    void aFillWithoutLiquidityAndARejectedOrderSayWhatTheyKnow() throws Exception {
        final Position after = new Position();
        after.init(100);
        ring.fillBooked(7, 100, 3L, 1, Side.Ask, 1, 1, 0, 3L, null, after);
        ring.orderClosed(7, 100, 1, 4L, 0, OrderState.REJECTED, RejectReason.POST_ONLY_WOULD_CROSS, 4L);

        agent.doWork();

        final JsonNode batch = mapper.readTree(posted.get(0));
        assertTrue(batch.get("fills").get(0).get("liquidity").isNull());
        final JsonNode close = batch.get("orderCloses").get(0);
        assertEquals("REJECTED", close.get("state").asText());
        assertEquals("POST_ONLY_WOULD_CROSS", close.get("rejectReason").asText());
    }

    @Test
    void sendsRefusalCountsAsRunningTotals() throws Exception {
        ring.orderRefused(100, RejectReason.HALTED);
        ring.orderRefused(100, RejectReason.HALTED);
        ring.orderRefused(100, RejectReason.INVALID_PRICE);
        ring.isFailing(1L);
        agent.doWork();

        ring.orderRefused(100, RejectReason.HALTED);
        ring.isFailing(2L);
        now[0] += FLUSH_MS;
        agent.doWork();
        assertEquals(1, posted.size(), "counts go out at most once a second");

        ring.isFailing(1_000_000_001L);
        now[0] += FLUSH_MS;
        agent.doWork();

        final JsonNode first = mapper.readTree(posted.get(0)).get("rejectCounts");
        assertEquals(2, first.size());
        assertEquals("HALTED", first.get(0).get("reason").asText());
        assertEquals(2, first.get(0).get("count").asLong());
        assertEquals(1, first.get(1).get("count").asLong());
        final JsonNode second = mapper.readTree(posted.get(1)).get("rejectCounts");
        assertEquals(1, second.size(), "only what changed");
        assertEquals(3, second.get(0).get("count").asLong(), "the total so far, not the change");
    }

    @Test
    void reportsItsHealthForTheSessionHeartbeat() {
        assertEquals(0, agent.lastAcceptedMs());
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 1, 1, 1L);
        statuses.add(503);

        agent.doWork();
        assertEquals(1, agent.consecutiveFailures());

        now[0] += 250;
        agent.doWork();
        assertEquals(0, agent.consecutiveFailures());
        assertEquals(now[0], agent.lastAcceptedMs());
    }

    @Test
    void aMarketOrderIsRecordedWithoutAPrice() throws Exception {
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, OrderDecoder.priceNullValue(), 10_000_000, 1L);

        agent.doWork();

        assertTrue(mapper.readTree(posted.get(0))
                .get("orderOpens")
                .get(0)
                .get("price")
                .isNull());
    }

    @Test
    void retriesTheSameBatchAfterAFailureAndConfirmsOnlyOnSuccess() throws Exception {
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 1, 1, 1L);
        statuses.add(503);
        statuses.add(-1);

        agent.doWork();
        assertTrue(ring.isFailing(1L + MAX_LAG_NS + 1), "not confirmed yet");
        agent.doWork();
        assertEquals(1, posted.size(), "waits out the backoff");

        now[0] += 250;
        agent.doWork();
        now[0] += 500;
        agent.doWork();

        assertEquals(3, posted.size());
        assertEquals(posted.get(0), posted.get(2), "the same batch, unchanged");
        assertFalse(ring.isFailing(1L + MAX_LAG_NS + 1));
    }

    @Test
    void aFencedSessionStopsWriting() {
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 1, 1, 1L);
        statuses.add(409);

        agent.doWork();
        now[0] += 10_000;
        ring.orderOpened(7, 100, 1, 4L, Side.Bid, 1, 1, 2L);
        agent.doWork();

        assertEquals(1, posted.size());
        assertTrue(ring.isFailing(0), "the OMS halts");
    }

    @Test
    void sendsAMarkOnlyWhenTheBookOrLastTradeMoves() throws Exception {
        prices.writeQuote(0, 30, 32);
        agent.doWork();
        assertEquals(1, posted.size());
        final JsonNode mark = mapper.readTree(posted.get(0)).get("marks").get(0);
        assertEquals(100, mark.get("listingId").asInt());
        assertEquals(30, mark.get("bid").asLong());
        assertEquals(32, mark.get("ask").asLong());

        now[0] += MARK_MS;
        agent.doWork();
        assertEquals(1, posted.size(), "nothing moved, nothing to send");

        prices.writeTrade(0, 31);
        now[0] += MARK_MS;
        agent.doWork();
        assertEquals(2, posted.size());
        assertEquals(
                31,
                mapper.readTree(posted.get(1))
                        .get("marks")
                        .get(0)
                        .get("lastTrade")
                        .asLong());
    }

    @Test
    void drainsABacklogWithoutWaitingAFlushInterval() {
        for (int i = 0; i < LedgerAgent.MAX_EVENTS_PER_BATCH + 5; i++) {
            ring.orderOpened(7, 100, 1, i, Side.Bid, 1, 1, 1L);
        }
        agent.doWork();
        agent.doWork();
        assertEquals(2, posted.size());
    }

    @Test
    void closingWritesWhatIsStillWaiting() {
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 1, 1, 1L);
        agent.onClose();
        assertEquals(1, posted.size());
        assertFalse(ring.isFailing(1L + MAX_LAG_NS + 1));
    }
}
