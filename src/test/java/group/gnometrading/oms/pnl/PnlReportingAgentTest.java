package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import group.gnometrading.RegistryConnection;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.schemas.Side;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PnlReportingAgentTest {

    private static final int MAX_SLOTS = 8;
    private static final int STRATEGY_ID = 3;
    private static final int LISTING_ID = 42;
    private static final long FLUSH_INTERVAL_MS = 1000;

    @Mock
    private RegistryConnection registryConnection;

    private SharedPositionBuffer buffer;
    private DefaultPositionTracker tracker;
    private PnlReportingAgent agent;
    private SharedPriceBuffer priceBuffer;
    private PriceSlotRegistry priceSlotRegistry;
    private PnlReportingAgent enrichedAgent;

    private long mockTime = 0;
    private final EpochClock clock = () -> mockTime;

    @BeforeEach
    void setUp() {
        buffer = new SharedPositionBuffer(MAX_SLOTS);
        tracker = new DefaultPositionTracker(buffer);
        agent = new PnlReportingAgent(
                tracker, registryConnection, clock, Duration.ofMillis(FLUSH_INTERVAL_MS), MAX_SLOTS);
        priceBuffer = new SharedPriceBuffer(MAX_SLOTS);
        priceSlotRegistry = new PriceSlotRegistry(MAX_SLOTS);
        enrichedAgent = new PnlReportingAgent(
                tracker,
                registryConnection,
                clock,
                Duration.ofMillis(FLUSH_INTERVAL_MS),
                MAX_SLOTS,
                priceBuffer,
                priceSlotRegistry);
    }

    private String triggerFlushAndCaptureJson(final PnlReportingAgent target) {
        target.onStart();
        mockTime = FLUSH_INTERVAL_MS + 1;
        target.doWork();

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(registryConnection, Mockito.atLeastOnce())
                .post(org.mockito.ArgumentMatchers.any(), bodyCaptor.capture(), lengthCaptor.capture());
        return new String(bodyCaptor.getValue(), 0, lengthCaptor.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    void testSnapshotAndFlushSendsRegisteredPositions() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 5);

        String json = triggerFlushAndCaptureJson(agent);
        assertTrue(json.contains("\"strategyId\":3"));
        assertTrue(json.contains("\"listingId\":42"));
        assertTrue(json.contains("\"netQuantity\":10"));
    }

    @Test
    void testNoPostWhenNoSlotsRegistered() {
        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS + 1;
        agent.doWork();

        verify(registryConnection, Mockito.never())
                .post(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void testMultipleSlotsAllSnapshots() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.registerSlot(STRATEGY_ID, LISTING_ID + 1);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 5, 200, 0);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID + 1, Side.Ask, 3, 150, 0);

        String json = triggerFlushAndCaptureJson(agent);
        assertEquals(2, countOccurrences(json, "\"strategyId\""));
    }

    @Test
    void testDoesNotFlushBeforeInterval() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);

        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS - 1;
        agent.doWork();

        verify(registryConnection, Mockito.never())
                .post(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    // --- mark price enrichment ---

    @Test
    void enrichment_longPosition_writesMarkPriceAndUnrealizedPnl() {
        // Long 10 @ avg 50, mark = 80 -> unrealizedPnl = 10 * (80 - 50) = 300
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 50, 0);
        int priceSlot = priceSlotRegistry.register(LISTING_ID);
        priceBuffer.write(priceSlot, 80L);

        String json = triggerFlushAndCaptureJson(enrichedAgent);
        assertTrue(json.contains("\"markPrice\":80"));
        assertTrue(json.contains("\"unrealizedPnl\":300"));
        assertTrue(json.contains("\"totalPnl\":300"));
    }

    @Test
    void enrichment_shortPosition_negativePnl() {
        // Short -10 @ avg 100, mark = 130 -> unrealizedPnl = -10 * (130 - 100) = -300
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 10, 100, 0);
        int priceSlot = priceSlotRegistry.register(LISTING_ID);
        priceBuffer.write(priceSlot, 130L);

        String json = triggerFlushAndCaptureJson(enrichedAgent);
        assertTrue(json.contains("\"markPrice\":130"));
        assertTrue(json.contains("\"unrealizedPnl\":-300"));
        assertTrue(json.contains("\"totalPnl\":-300"));
    }

    @Test
    void enrichment_combinedRealizedAndUnrealized_totalPnlIsSum() {
        // Realize +200, then hold 5 long with mark moving against: unrealized = -100
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 5, 140, 0);
        // realizedPnl = 5 * (140 - 100) = 200, remaining long 5 @ avg 100
        // mark = 80 -> unrealizedPnl = 5 * (80 - 100) = -100 -> totalPnl = 100
        int priceSlot = priceSlotRegistry.register(LISTING_ID);
        priceBuffer.write(priceSlot, 80L);

        String json = triggerFlushAndCaptureJson(enrichedAgent);
        assertTrue(json.contains("\"unrealizedPnl\":-100"));
        assertTrue(json.contains("\"totalPnl\":100"));
    }

    @Test
    void enrichment_noMarkPriceWritten_fieldsRemainZero() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 50, 0);
        priceSlotRegistry.register(LISTING_ID);
        // price never written — readSpinning returns 0

        String json = triggerFlushAndCaptureJson(enrichedAgent);
        assertTrue(json.contains("\"markPrice\":0"));
        assertTrue(json.contains("\"unrealizedPnl\":0"));
    }

    @Test
    void enrichment_listingNotInPriceRegistry_fieldsRemainZero() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 50, 0);
        // LISTING_ID not registered in priceSlotRegistry

        String json = triggerFlushAndCaptureJson(enrichedAgent);
        assertTrue(json.contains("\"markPrice\":0"));
        assertTrue(json.contains("\"unrealizedPnl\":0"));
    }

    @Test
    void enrichment_nullPriceBuffer_fieldsRemainZero() {
        // 5-arg constructor passes null for both price buffer and registry
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 50, 0);

        String json = triggerFlushAndCaptureJson(agent);
        assertTrue(json.contains("\"markPrice\":0"));
        assertTrue(json.contains("\"unrealizedPnl\":0"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
