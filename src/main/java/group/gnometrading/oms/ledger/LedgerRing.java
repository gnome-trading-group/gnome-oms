package group.gnometrading.oms.ledger;

import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;

/**
 * Hands the OMS's ledger events to the {@link LedgerAgent}, which writes them to the registry.
 *
 * <p>The OMS thread publishes; the agent thread reads, and once the registry has confirmed a batch, acknowledges it.
 * The OMS judges the ledger's health from its own side: it stamps each event as it publishes it, so the age of the
 * oldest unconfirmed event is known without trusting the agent's thread to be running at all.
 *
 * <p>An event that doesn't fit is lost. The OMS halts trading, and a GAP is recorded for that strategy and listing
 * as soon as there is room, so its position is reviewed before anything trusts it again.
 */
public final class LedgerRing implements LedgerSink {

    private static final int MAX_PENDING_GAPS = 64;

    private final OneToOneRingBuffer<LedgerEvent> ring;
    private final PositionTracker positions;
    private final long maxLagNs;

    // Publish time of event n, at n % length. Unconfirmed events are those still in the ring plus the agent's one
    // batch in flight, so this window always covers every one of them.
    private final long[] publishTimes;
    private long published;
    private volatile long acknowledged;
    private volatile boolean fenced;
    private boolean overflowed;
    private int claimedIndex;

    private final int[] gapStrategies = new int[MAX_PENDING_GAPS];
    private final int[] gapListings = new int[MAX_PENDING_GAPS];
    private final long[] gapCounters = new long[MAX_PENDING_GAPS];
    private int gapCount;

    public LedgerRing(
            final int capacity, final int maxBatchEvents, final long maxLagNs, final PositionTracker positions) {
        this.ring = new OneToOneRingBuffer<>(LedgerEvent[]::new, LedgerEvent::new, capacity);
        this.publishTimes = new long[capacity + maxBatchEvents];
        this.maxLagNs = maxLagNs;
        this.positions = positions;
    }

    @Override
    public void orderOpened(
            final int strategyId,
            final int listingId,
            final int exchangeId,
            final long clientOidCounter,
            final Side side,
            final long price,
            final long size,
            final long timeNs) {
        final LedgerEvent event = claim(strategyId, listingId, clientOidCounter, timeNs);
        if (event == null) {
            return;
        }
        event.type = LedgerEventType.ORDER_OPENED;
        event.exchangeId = exchangeId;
        event.side = side;
        event.price = price;
        event.size = size;
        commit();
    }

    @Override
    public void orderAcked(
            final int strategyId,
            final int listingId,
            final int exchangeId,
            final long clientOidCounter,
            final OrderExecutionReport report) {
        final long timeNs = report.decoder.timestampRecv();
        final LedgerEvent event = claim(strategyId, listingId, clientOidCounter, timeNs);
        if (event == null) {
            return;
        }
        event.type = LedgerEventType.ORDER_ACKED;
        event.exchangeId = exchangeId;
        report.decoder.getExchangeOrderId(event.exchangeOrderId, 0);
        // The field is padded with zeros after the id.
        int length = 0;
        while (length < LedgerEvent.EXCHANGE_ORDER_ID_LENGTH && event.exchangeOrderId[length] != 0) {
            length++;
        }
        event.exchangeOrderIdLength = length;
        commit();
    }

    @Override
    public void fillBooked(
            final int strategyId,
            final int listingId,
            final long clientOidCounter,
            final long cumQtyAfter,
            final Side side,
            final long qty,
            final long price,
            final long fee,
            final long eventTimeNs,
            final Position after) {
        final LedgerEvent event = claim(strategyId, listingId, clientOidCounter, eventTimeNs);
        if (event == null) {
            return;
        }
        event.type = LedgerEventType.FILL;
        event.cumQtyAfter = cumQtyAfter;
        event.side = side;
        event.size = qty;
        event.price = price;
        event.fee = fee;
        event.setPosition(after);
        commit();
    }

    @Override
    public void orderClosed(
            final int strategyId,
            final int listingId,
            final int exchangeId,
            final long clientOidCounter,
            final long filledQty,
            final long timeNs) {
        final LedgerEvent event = claim(strategyId, listingId, clientOidCounter, timeNs);
        if (event == null) {
            return;
        }
        event.type = LedgerEventType.ORDER_CLOSED;
        event.exchangeId = exchangeId;
        event.cumQtyAfter = filledQty;
        commit();
    }

    @Override
    public boolean isFailing(final long nowNs) {
        publishPendingGaps(nowNs);
        if (overflowed || fenced) {
            return true;
        }
        final long confirmed = acknowledged;
        return confirmed < published && nowNs - publishTimes[slotOf(confirmed)] > maxLagNs;
    }

    /** Agent thread: reads up to {@code limit} events. */
    public void read(final MessageConsumer<LedgerEvent> consumer, final int limit) {
        ring.read(consumer, limit);
    }

    /** Agent thread: the registry has stored the next {@code count} events. */
    public void acknowledge(final int count) {
        acknowledged = acknowledged + count;
    }

    /** Agent thread: the registry refuses this session's writes, so nothing more it does can be recorded. */
    public void fence() {
        fenced = true;
    }

    public boolean isFenced() {
        return fenced;
    }

    private LedgerEvent claim(
            final int strategyId, final int listingId, final long clientOidCounter, final long timeNs) {
        final int index = ring.tryClaim();
        if (index < 0) {
            overflowed = true;
            recordGap(strategyId, listingId, clientOidCounter);
            return null;
        }
        publishTimes[slotOf(published)] = timeNs;
        final LedgerEvent event = ring.indexAt(index);
        event.listingId = listingId;
        event.clientOidCounter = clientOidCounter;
        event.eventTimeNs = timeNs;
        event.exchangeOrderIdLength = 0;
        claimedIndex = index;
        return event;
    }

    private void commit() {
        ring.commit(claimedIndex);
        published++;
    }

    private int slotOf(final long sequence) {
        return (int) (sequence % publishTimes.length);
    }

    private void recordGap(final int strategyId, final int listingId, final long clientOidCounter) {
        for (int i = 0; i < gapCount; i++) {
            if (gapStrategies[i] == strategyId && gapListings[i] == listingId) {
                return;
            }
        }
        if (gapCount < MAX_PENDING_GAPS) {
            gapStrategies[gapCount] = strategyId;
            gapListings[gapCount] = listingId;
            gapCounters[gapCount] = clientOidCounter;
            gapCount++;
        }
    }

    private void publishPendingGaps(final long nowNs) {
        while (gapCount > 0) {
            final int last = gapCount - 1;
            final int index = ring.tryClaim();
            if (index < 0) {
                return;
            }
            publishTimes[slotOf(published)] = nowNs;
            final LedgerEvent event = ring.indexAt(index);
            event.type = LedgerEventType.GAP;
            event.listingId = gapListings[last];
            event.clientOidCounter = gapCounters[last];
            event.eventTimeNs = nowNs;
            event.exchangeOrderIdLength = 0;
            // A gap carries the position as the OMS holds it, which the lost events already moved it to.
            final Position position = positions.getStrategyPosition(gapStrategies[last], gapListings[last]);
            if (position != null) {
                event.setPosition(position);
            } else {
                event.netQuantityAfter = 0;
                event.totalCostAfter = 0;
                event.realizedPnlAfter = 0;
                event.feesAfter = 0;
                event.positionVersion = 0;
            }
            ring.commit(index);
            published++;
            gapCount = last;
        }
    }
}
