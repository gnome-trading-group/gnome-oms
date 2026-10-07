package group.gnometrading.oms.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerRingTest {

    private static final long MAX_LAG_NS = 10_000_000_000L;

    private final DefaultPositionTracker positions = new DefaultPositionTracker(new SharedPositionBuffer(8));

    @Test
    void anEventWaitingTooLongToBeConfirmedFailsTheLedger() {
        final LedgerRing ring = new LedgerRing(8, 4, MAX_LAG_NS, positions);
        ring.orderOpened(7, 100, 1, 1L, Side.Bid, 50, 10, 1_000L);

        assertFalse(ring.isFailing(1_000L + MAX_LAG_NS));
        assertTrue(ring.isFailing(1_000L + MAX_LAG_NS + 1));

        drain(ring);
        ring.acknowledge(1);
        assertFalse(ring.isFailing(1_000L + MAX_LAG_NS + 1), "confirmed");
    }

    @Test
    void lagIsMeasuredFromTheOldestUnconfirmedEvent() {
        final LedgerRing ring = new LedgerRing(8, 4, MAX_LAG_NS, positions);
        ring.orderOpened(7, 100, 1, 1L, Side.Bid, 50, 10, 1_000L);
        ring.orderOpened(7, 100, 1, 2L, Side.Bid, 50, 10, 5_000_000_000L);
        drain(ring);
        ring.acknowledge(1);

        assertFalse(ring.isFailing(5_000_000_000L + MAX_LAG_NS));
        assertTrue(ring.isFailing(5_000_000_001L + MAX_LAG_NS));
    }

    @Test
    void aFencedSessionFailsTheLedger() {
        final LedgerRing ring = new LedgerRing(8, 4, MAX_LAG_NS, positions);
        ring.fence();
        assertTrue(ring.isFailing(0));
    }

    @Test
    void aLostEventFailsTheLedgerAndRecordsAGapWithThePositionOnceThereIsRoom() {
        positions.seedStrategyPosition(7, 100, 3_000_000, 1_500_000_000L, 4);
        final LedgerRing ring = new LedgerRing(2, 4, MAX_LAG_NS, positions);
        ring.orderOpened(7, 100, 1, 1L, Side.Bid, 50, 10, 1L);
        ring.orderOpened(7, 100, 1, 2L, Side.Bid, 50, 10, 2L);
        ring.orderOpened(7, 100, 1, 3L, Side.Bid, 50, 10, 3L);
        ring.orderOpened(7, 100, 1, 4L, Side.Bid, 50, 10, 4L);

        assertTrue(ring.isFailing(5L), "the third and fourth events were lost");
        assertEquals(List.of(LedgerEventType.ORDER_OPENED, LedgerEventType.ORDER_OPENED), types(drain(ring)));

        assertTrue(ring.isFailing(6L), "a lost event fails the ledger for good");
        final List<LedgerEvent> gaps = drain(ring);
        assertEquals(1, gaps.size(), "one gap per strategy and listing");
        final LedgerEvent gap = gaps.get(0);
        assertEquals(LedgerEventType.GAP, gap.type);
        assertEquals(100, gap.listingId);
        assertEquals(3L, gap.clientOidCounter, "the first event lost");
        assertEquals(3_000_000, gap.netQuantityAfter);
        assertEquals(1_500_000_000L, gap.totalCostAfter);
        assertEquals(4, gap.positionVersion);
    }

    @Test
    void anAckCarriesTheVenueIdWithoutItsPadding() {
        final LedgerRing ring = new LedgerRing(8, 4, MAX_LAG_NS, positions);
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder.exchangeOrderId("4071-100-1").timestampRecv(9L);

        ring.orderAcked(7, 100, 1, 1L, report);

        final LedgerEvent ack = drain(ring).get(0);
        assertEquals(LedgerEventType.ORDER_ACKED, ack.type);
        assertEquals("4071-100-1", new String(ack.exchangeOrderId, 0, ack.exchangeOrderIdLength));
        assertEquals(9L, ack.eventTimeNs);
    }

    @Test
    void refusalCountsWaitForRoomInsteadOfFailingTheLedger() {
        final LedgerRing ring = new LedgerRing(2, 4, MAX_LAG_NS, positions);
        ring.orderOpened(7, 100, 1, 1L, Side.Bid, 50, 10, 1L);
        ring.orderOpened(7, 100, 1, 2L, Side.Bid, 50, 10, 1L);
        for (int i = 0; i < 1_000; i++) {
            ring.orderRefused(100, RejectReason.RISK_LIMIT_EXCEEDED);
        }

        assertFalse(ring.isFailing(2L), "a full ring delays counts; it never loses positions over them");
        assertEquals(List.of(LedgerEventType.ORDER_OPENED, LedgerEventType.ORDER_OPENED), types(drain(ring)));
        ring.acknowledge(2);

        assertFalse(ring.isFailing(1_000_000_003L));
        final LedgerEvent count = drain(ring).get(0);
        assertEquals(LedgerEventType.REJECT_COUNT, count.type);
        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, count.rejectReason);
        assertEquals(1_000, count.count);
    }

    private static List<LedgerEvent> drain(final LedgerRing ring) {
        final List<LedgerEvent> events = new ArrayList<>();
        ring.read(
                event -> {
                    final LedgerEvent copy = new LedgerEvent();
                    copy.copyFrom(event);
                    events.add(copy);
                },
                64);
        return events;
    }

    private static List<LedgerEventType> types(final List<LedgerEvent> events) {
        return events.stream().map(event -> event.type).toList();
    }
}
