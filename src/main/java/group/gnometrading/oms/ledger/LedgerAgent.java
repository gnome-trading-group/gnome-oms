package group.gnometrading.oms.ledger;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.EpochClock;

/**
 * Writes the OMS's ledger events, and the marks of the listings it trades, to the registry.
 *
 * <p>Runs on its own thread. It posts only when there is something to write, at most every flush interval, with one
 * request in flight: a failed request is retried, unchanged, with growing backoff, and the batch is acknowledged to
 * the {@link LedgerRing} only once the registry has stored it. Marks ride along only when a listing's top of book or
 * last trade changed, at most every mark interval.
 *
 * <p>Nothing here may stop the process: every failure is logged and retried, and the OMS, which watches the ring,
 * decides when the ledger has fallen too far behind to keep trading.
 */
public final class LedgerAgent implements GnomeAgent {

    /** Keeps a batch's body well inside the HTTP client's 32 KB write buffer. */
    public static final int MAX_EVENTS_PER_BATCH = 40;

    private static final GnomeString PATH = new ViewString("/api/ledger/batch");
    private static final int BODY_CAPACITY = 28 * 1024;
    private static final long MIN_BACKOFF_MS = 250;
    private static final long MAX_BACKOFF_MS = 5_000;
    private static final long MAX_PARK_MS = 50;
    private static final int CLOSE_ATTEMPTS = 3;
    private static final int HTTP_OK = 200;
    private static final int HTTP_CLIENT_ERROR = 400;
    private static final int HTTP_SERVER_ERROR = 500;
    // The ledger stores the side as a number.
    private static final int BUY = 0;
    private static final int SELL = 1;

    private final LedgerRing ring;
    private final RegistryConnection registry;
    private final EpochClock clock;
    private final Logger logger;
    private final String sessionId;
    private final SharedPriceBuffer prices;
    private final PriceSlotRegistry priceSlots;
    private final long flushIntervalMs;
    private final long markIntervalMs;

    private final LedgerEvent[] batch = new LedgerEvent[MAX_EVENTS_PER_BATCH];
    private int batchCount;
    private final long[] top = new long[3];
    // Per price slot: the top of book and last trade last stored, and any newer one waiting to be.
    private final long[][] sentMarks;
    private final long[][] pendingMarks;
    private final long[] pendingMarkTimes;
    private final boolean[] markPending;

    private final ByteBuffer body = ByteBuffer.allocate(BODY_CAPACITY);
    private final JsonEncoder json = new JsonEncoder();
    private final MutableString exchangeOrderId = new MutableString(LedgerEvent.EXCHANGE_ORDER_ID_LENGTH);
    private boolean bodyReady;

    private long nextFlushMs;
    private long nextMarkMs;
    private long nextAttemptMs;
    private long backoffMs = MIN_BACKOFF_MS;

    public LedgerAgent(
            final LedgerRing ring,
            final RegistryConnection registry,
            final EpochClock clock,
            final Logger logger,
            final String sessionId,
            final SharedPriceBuffer prices,
            final PriceSlotRegistry priceSlots,
            final long flushIntervalMs,
            final long markIntervalMs) {
        this.ring = ring;
        this.registry = registry;
        this.clock = clock;
        this.logger = logger;
        this.sessionId = sessionId;
        this.prices = prices;
        this.priceSlots = priceSlots;
        this.flushIntervalMs = flushIntervalMs;
        this.markIntervalMs = markIntervalMs;
        for (int i = 0; i < MAX_EVENTS_PER_BATCH; i++) {
            this.batch[i] = new LedgerEvent();
        }
        final int slots = prices.capacity();
        this.sentMarks = new long[slots][3];
        this.pendingMarks = new long[slots][3];
        this.pendingMarkTimes = new long[slots];
        this.markPending = new boolean[slots];
    }

    @Override
    public int doWork() {
        try {
            if (ring.isFenced()) {
                park(MAX_PARK_MS);
                return 0;
            }
            final long now = clock.time();
            if (!bodyReady) {
                if (now >= nextMarkMs) {
                    nextMarkMs = now + markIntervalMs;
                    collectMarks(now);
                }
                if (now >= nextFlushMs) {
                    nextFlushMs = now + flushIntervalMs;
                    prepareBatch();
                }
            }
            if (bodyReady && now >= nextAttemptMs) {
                send(now);
                return 1;
            }
            park(Math.min(MAX_PARK_MS, Math.max(1, nextWakeMs(now) - now)));
            return 0;
        } catch (Throwable error) {
            logger.logf(LogMessage.LEDGER_WRITE_FAILED, "Ledger agent error: %s", error);
            return 0;
        }
    }

    /** One last write of everything still waiting, so a clean stop loses nothing. */
    @Override
    public void onClose() {
        try {
            for (int attempt = 0; attempt < CLOSE_ATTEMPTS && !ring.isFenced(); ) {
                if (!bodyReady) {
                    collectMarks(clock.time());
                    prepareBatch();
                    if (!bodyReady) {
                        return;
                    }
                }
                if (!send(clock.time())) {
                    attempt++;
                    park(backoffMs);
                }
            }
        } catch (Throwable error) {
            logger.logf(LogMessage.LEDGER_WRITE_FAILED, "Ledger agent final flush failed: %s", error);
        }
    }

    private long nextWakeMs(final long now) {
        if (bodyReady) {
            return nextAttemptMs;
        }
        return Math.min(nextFlushMs, nextMarkMs);
    }

    private void collectMarks(final long now) {
        for (int slot = 0; slot < priceSlots.count(); slot++) {
            prices.readTop(slot, top);
            final long[] sent = sentMarks[slot];
            if (top[0] == sent[0] && top[1] == sent[1] && top[2] == sent[2]) {
                markPending[slot] = false;
                continue;
            }
            System.arraycopy(top, 0, pendingMarks[slot], 0, 3);
            pendingMarkTimes[slot] = now;
            markPending[slot] = true;
        }
    }

    private void prepareBatch() {
        batchCount = 0;
        ring.read(this::addToBatch, MAX_EVENTS_PER_BATCH);
        boolean anyMark = false;
        for (int slot = 0; slot < priceSlots.count(); slot++) {
            anyMark |= markPending[slot];
        }
        if (batchCount == 0 && !anyMark) {
            return;
        }
        encode();
        bodyReady = true;
        nextAttemptMs = 0;
    }

    private void addToBatch(final LedgerEvent event) {
        batch[batchCount++].copyFrom(event);
    }

    /** @return whether the batch is settled: stored, or refused for good */
    private boolean send(final long now) {
        final int status = registry.tryPost(PATH, body.array(), body.position());
        if (status == HTTP_OK) {
            ring.acknowledge(batchCount);
            for (int slot = 0; slot < priceSlots.count(); slot++) {
                if (markPending[slot]) {
                    System.arraycopy(pendingMarks[slot], 0, sentMarks[slot], 0, 3);
                    markPending[slot] = false;
                }
            }
            final boolean full = batchCount == MAX_EVENTS_PER_BATCH;
            bodyReady = false;
            batchCount = 0;
            backoffMs = MIN_BACKOFF_MS;
            if (full) {
                // More is likely waiting in the ring, so drain it now rather than a flush interval from now.
                nextFlushMs = now;
            }
            return true;
        }
        if (status >= HTTP_CLIENT_ERROR && status < HTTP_SERVER_ERROR) {
            // Fenced (the session no longer holds its listings, or has ended) or refused as malformed: retrying the
            // same batch can't succeed, so stop writing and let the OMS halt.
            logger.log(LogMessage.LEDGER_FENCED, status);
            ring.fence();
            bodyReady = false;
            return true;
        }
        logger.log(LogMessage.LEDGER_WRITE_FAILED, status);
        nextAttemptMs = now + backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        return false;
    }

    private void encode() {
        body.clear();
        json.wrap(body);
        json.writeObjectStart().writeObjectEntry("sessionId", sessionId);
        writeFills();
        writeOrderOpens();
        writeOrderAcks();
        writeOrderCloses();
        writeMarks();
        json.writeObjectEnd();
    }

    private void writeFills() {
        json.writeComma().writeString("fills").writeColon().writeArrayStart();
        boolean first = true;
        for (int i = 0; i < batchCount; i++) {
            final LedgerEvent event = batch[i];
            if (event.type == LedgerEventType.FILL || event.type == LedgerEventType.GAP) {
                first = separate(first);
                writeFill(event);
            }
        }
        json.writeArrayEnd();
    }

    private void writeOrderOpens() {
        json.writeComma().writeString("orderOpens").writeColon().writeArrayStart();
        boolean first = true;
        for (int i = 0; i < batchCount; i++) {
            final LedgerEvent event = batch[i];
            if (event.type == LedgerEventType.ORDER_OPENED) {
                first = separate(first);
                writeOrderKey(event);
                json.writeComma()
                        .writeObjectEntry("side", sideOf(event.side))
                        .writeComma()
                        .writeObjectEntry("price", event.price)
                        .writeComma()
                        .writeObjectEntry("size", event.size)
                        .writeObjectEnd();
            }
        }
        json.writeArrayEnd();
    }

    private void writeOrderAcks() {
        json.writeComma().writeString("orderAcks").writeColon().writeArrayStart();
        boolean first = true;
        for (int i = 0; i < batchCount; i++) {
            final LedgerEvent event = batch[i];
            if (event.type == LedgerEventType.ORDER_ACKED) {
                first = separate(first);
                writeOrderKey(event);
                exchangeOrderId.reset();
                for (int b = 0; b < event.exchangeOrderIdLength; b++) {
                    exchangeOrderId.append(event.exchangeOrderId[b]);
                }
                json.writeComma()
                        .writeObjectEntry("exchangeOrderId", exchangeOrderId)
                        .writeObjectEnd();
            }
        }
        json.writeArrayEnd();
    }

    private void writeOrderCloses() {
        json.writeComma().writeString("orderCloses").writeColon().writeArrayStart();
        boolean first = true;
        for (int i = 0; i < batchCount; i++) {
            final LedgerEvent event = batch[i];
            if (event.type == LedgerEventType.ORDER_CLOSED) {
                first = separate(first);
                writeOrderKey(event);
                json.writeComma()
                        .writeObjectEntry("filledQty", event.cumQtyAfter)
                        .writeObjectEnd();
            }
        }
        json.writeArrayEnd();
    }

    private void writeMarks() {
        json.writeComma().writeString("marks").writeColon().writeArrayStart();
        boolean first = true;
        for (int slot = 0; slot < priceSlots.count(); slot++) {
            if (markPending[slot]) {
                first = separate(first);
                final long[] mark = pendingMarks[slot];
                json.writeObjectStart()
                        .writeObjectEntry("listingId", priceSlots.listingId(slot))
                        .writeComma()
                        .writeObjectEntry("tsMs", pendingMarkTimes[slot])
                        .writeComma()
                        .writeObjectEntry("bid", mark[0])
                        .writeComma()
                        .writeObjectEntry("ask", mark[1])
                        .writeComma()
                        .writeObjectEntry("lastTrade", mark[2])
                        .writeObjectEnd();
            }
        }
        json.writeArrayEnd();
    }

    private boolean separate(final boolean first) {
        if (!first) {
            json.writeComma();
        }
        return false;
    }

    private void writeFill(final LedgerEvent event) {
        final boolean gap = event.type == LedgerEventType.GAP;
        json.writeObjectStart()
                .writeObjectEntry("source", gap ? "GAP" : "VENUE")
                .writeComma()
                .writeObjectEntry("listingId", event.listingId)
                .writeComma()
                .writeObjectEntry("clientOidCounter", event.clientOidCounter)
                .writeComma()
                .writeObjectEntry("eventTimeNs", event.eventTimeNs)
                .writeComma()
                .writeObjectEntry("netQuantityAfter", event.netQuantityAfter)
                .writeComma()
                .writeObjectEntry("totalCostAfter", event.totalCostAfter)
                .writeComma()
                .writeObjectEntry("realizedPnlAfter", event.realizedPnlAfter)
                .writeComma()
                .writeObjectEntry("feesAfter", event.feesAfter)
                .writeComma()
                .writeObjectEntry("positionVersion", event.positionVersion);
        if (gap) {
            json.writeComma().writeObjectEntry("reason", "ledger events lost");
        } else {
            json.writeComma()
                    .writeObjectEntry("cumQtyAfter", event.cumQtyAfter)
                    .writeComma()
                    .writeObjectEntry("side", sideOf(event.side))
                    .writeComma()
                    .writeObjectEntry("fillQty", event.size)
                    .writeComma()
                    .writeObjectEntry("fillPrice", event.price)
                    .writeComma()
                    .writeObjectEntry("fee", event.fee);
        }
        json.writeObjectEnd();
    }

    private void writeOrderKey(final LedgerEvent event) {
        json.writeObjectStart()
                .writeObjectEntry("clientOidCounter", event.clientOidCounter)
                .writeComma()
                .writeObjectEntry("listingId", event.listingId)
                .writeComma()
                .writeObjectEntry("exchangeId", event.exchangeId)
                .writeComma()
                .writeObjectEntry("eventTimeNs", event.eventTimeNs);
    }

    private static int sideOf(final Side side) {
        return side == Side.Bid ? BUY : SELL;
    }

    private static void park(final long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
