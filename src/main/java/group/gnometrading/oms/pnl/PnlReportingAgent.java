package group.gnometrading.oms.pnl;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.position.StrategyPositionConsumer;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.Schedule;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;

/**
 * Periodically snapshots all per-strategy positions from {@link PositionTracker} and flushes
 * them to the registry. Runs on its own thread via {@link group.gnometrading.concurrent.GnomeAgentRunner}.
 *
 * <p>Reads positions via {@link PositionTracker#forEachStrategyPosition}, which is backed by
 * {@link group.gnometrading.oms.position.SharedPositionBuffer} for thread-safe cross-thread reads.
 */
public final class PnlReportingAgent implements GnomeAgent, StrategyPositionConsumer {

    private static final String PNL_SNAPSHOTS_PATH = "/api/pnl/snapshots";
    private static final int BYTES_PER_SNAPSHOT = 384;
    private final PositionTracker positionTracker;
    private final RegistryConnection registryConnection;
    private final Schedule flushSchedule;
    private final GnomeString path;
    private final PnlSnapshot[] snapshots;
    private final ByteBuffer bodyBuffer;
    private final JsonEncoder jsonEncoder;
    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;

    private int pendingCount;

    public PnlReportingAgent(
            final PositionTracker positionTracker,
            final RegistryConnection registryConnection,
            final EpochClock clock,
            final Duration flushInterval,
            final int maxSlots) {
        this(positionTracker, registryConnection, clock, flushInterval, maxSlots, null, null);
    }

    public PnlReportingAgent(
            final PositionTracker positionTracker,
            final RegistryConnection registryConnection,
            final EpochClock clock,
            final Duration flushInterval,
            final int maxSlots,
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry) {
        this.positionTracker = positionTracker;
        this.registryConnection = registryConnection;
        this.flushSchedule = new Schedule(clock, flushInterval.toMillis(), this::snapshotAndFlush);
        this.path = new ViewString(PNL_SNAPSHOTS_PATH);
        this.snapshots = new PnlSnapshot[maxSlots];
        for (int i = 0; i < maxSlots; i++) {
            this.snapshots[i] = new PnlSnapshot();
        }
        this.bodyBuffer = ByteBuffer.allocate(maxSlots * BYTES_PER_SNAPSHOT);
        this.jsonEncoder = new JsonEncoder();
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
    }

    @Override
    public void onStart() {
        flushSchedule.start();
    }

    @Override
    public int doWork() {
        flushSchedule.check();
        return 0;
    }

    @Override
    public void accept(final int strategyId, final int listingId, final Position position) {
        snapshots[pendingCount++].set(strategyId, listingId, position);
    }

    private void snapshotAndFlush() {
        pendingCount = 0;
        positionTracker.forEachStrategyPosition(this);

        if (pendingCount == 0) {
            return;
        }

        enrichWithMarkPrices();

        bodyBuffer.clear();
        jsonEncoder.wrap(bodyBuffer);
        buildJson();
        registryConnection.post(path, bodyBuffer.array(), bodyBuffer.position());
    }

    private void enrichWithMarkPrices() {
        if (priceBuffer == null || priceSlotRegistry == null) {
            return;
        }
        for (int i = 0; i < pendingCount; i++) {
            final PnlSnapshot snap = snapshots[i];
            final int slot = priceSlotRegistry.getSlot(snap.listingId);
            if (slot == IntToIntHashMap.MISSING) {
                continue;
            }
            final long markPrice = priceBuffer.readSpinning(slot);
            if (markPrice == 0) {
                continue;
            }
            snap.markPrice = markPrice;
            snap.unrealizedPnl = snap.netQuantity * (markPrice - snap.avgEntryPrice);
        }
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
                .writeObjectEntry("realizedPnl", snapshot.realizedPnl)
                .writeComma()
                .writeObjectEntry("unrealizedPnl", snapshot.unrealizedPnl)
                .writeComma()
                .writeObjectEntry("totalPnl", snapshot.realizedPnl + snapshot.unrealizedPnl)
                .writeComma()
                .writeObjectEntry("markPrice", snapshot.markPrice)
                .writeComma()
                .writeObjectEntry("totalFees", snapshot.totalFees)
                .writeComma()
                .writeObjectEntry("leavesBuyQty", snapshot.leavesBuyQty)
                .writeComma()
                .writeObjectEntry("leavesSellQty", snapshot.leavesSellQty)
                .writeObjectEnd();
    }
}
