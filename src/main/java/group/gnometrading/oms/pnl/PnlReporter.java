package group.gnometrading.oms.pnl;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.oms.position.Position;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Enqueues position snapshots and periodically flushes them to the registry.
 * GC-free on the hot path: pre-allocated snapshot structs, ByteBuffer JSON builder.
 * One small allocation (Arrays.copyOf) occurs per flush inside RegistryConnection.
 */
public final class PnlReporter {

    private static final String PNL_SNAPSHOTS_PATH = "/api/pnl/snapshots";
    private static final int DEFAULT_CAPACITY = 32;
    private static final int BYTES_PER_SNAPSHOT = 256;
    private static final int REALIZED_PNL_SCALE = 6;

    private final RegistryConnection registryConnection;
    private final GnomeString path;
    private final long flushIntervalMs;
    private final PnlSnapshot[] snapshots;
    private final ByteBuffer bodyBuffer;
    private final JsonEncoder jsonEncoder;

    private int pendingCount;
    private long lastFlushMs;

    public PnlReporter(final RegistryConnection registryConnection, final Duration flushInterval) {
        this(registryConnection, flushInterval, DEFAULT_CAPACITY);
    }

    public PnlReporter(final RegistryConnection registryConnection, final Duration flushInterval, final int capacity) {
        this.registryConnection = registryConnection;
        this.flushIntervalMs = flushInterval.toMillis();
        this.path = new ViewString(PNL_SNAPSHOTS_PATH);
        this.snapshots = new PnlSnapshot[capacity];
        for (int i = 0; i < capacity; i++) {
            this.snapshots[i] = new PnlSnapshot();
        }
        this.bodyBuffer = ByteBuffer.allocate(capacity * BYTES_PER_SNAPSHOT);
        this.jsonEncoder = new JsonEncoder();
        this.pendingCount = 0;
        this.lastFlushMs = 0;
    }

    public void enqueue(final int strategyId, final int listingId, final Position position) {
        if (pendingCount >= snapshots.length) {
            flush();
        }
        final PnlSnapshot snapshot = snapshots[pendingCount++];
        snapshot.strategyId = strategyId;
        snapshot.listingId = listingId;
        snapshot.netQuantity = position.netQuantity;
        snapshot.avgEntryPrice = position.avgEntryPrice;
        snapshot.realizedPnl = position.realizedPnl;
        snapshot.totalFees = position.totalFees;
        snapshot.leavesBuyQty = position.leavesBuyQty;
        snapshot.leavesSellQty = position.leavesSellQty;
    }

    public void maybeFlush(final long nowEpochMs) {
        if (pendingCount > 0 && nowEpochMs - lastFlushMs >= flushIntervalMs) {
            flush();
        }
    }

    public void flush() {
        if (pendingCount == 0) {
            return;
        }
        bodyBuffer.clear();
        jsonEncoder.wrap(bodyBuffer);
        buildJson();
        registryConnection.post(path, bodyBuffer.array(), bodyBuffer.position());
        pendingCount = 0;
        lastFlushMs = System.currentTimeMillis();
    }

    private void buildJson() {
        jsonEncoder.writeArrayStart();
        for (int i = 0; i < pendingCount; i++) {
            if (i > 0) {
                jsonEncoder.writeComma();
            }
            appendSnapshot(snapshots[i]);
        }
        jsonEncoder.writeArrayEnd();
    }

    private void appendSnapshot(final PnlSnapshot snapshot) {
        jsonEncoder
                .writeObjectStart()
                .writeObjectEntry("strategyId", snapshot.strategyId)
                .writeComma()
                .writeObjectEntry("listingId", snapshot.listingId)
                .writeComma()
                .writeObjectEntry("netQuantity", snapshot.netQuantity)
                .writeComma()
                .writeObjectEntry("avgEntryPrice", snapshot.avgEntryPrice)
                .writeComma()
                .writeObjectEntry("realizedPnl", snapshot.realizedPnl, REALIZED_PNL_SCALE)
                .writeComma()
                .writeObjectEntry("totalFees", snapshot.totalFees)
                .writeComma()
                .writeObjectEntry("leavesBuyQty", snapshot.leavesBuyQty)
                .writeComma()
                .writeObjectEntry("leavesSellQty", snapshot.leavesSellQty)
                .writeObjectEnd();
    }
}
