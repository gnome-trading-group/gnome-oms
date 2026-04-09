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

    private long mockTime = 0;
    private final EpochClock clock = () -> mockTime;

    @BeforeEach
    void setUp() {
        buffer = new SharedPositionBuffer(MAX_SLOTS);
        tracker = new DefaultPositionTracker(buffer);
        agent = new PnlReportingAgent(
                tracker, registryConnection, clock, Duration.ofMillis(FLUSH_INTERVAL_MS), MAX_SLOTS);
    }

    @Test
    void testSnapshotAndFlushSendsRegisteredPositions() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 5);

        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS + 1;
        agent.doWork();

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(registryConnection)
                .post(org.mockito.ArgumentMatchers.any(), bodyCaptor.capture(), lengthCaptor.capture());

        String json = new String(bodyCaptor.getValue(), 0, lengthCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"strategyId\":3"));
        assertTrue(json.contains("\"listingId\":42"));
        assertTrue(json.contains("\"netQuantity\":10"));
    }

    @Test
    void testNoPostWhenNoSlotsRegistered() {
        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS + 1;
        agent.doWork();

        verify(registryConnection, org.mockito.Mockito.never())
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

        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS + 1;
        agent.doWork();

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(registryConnection)
                .post(org.mockito.ArgumentMatchers.any(), bodyCaptor.capture(), lengthCaptor.capture());

        String json = new String(bodyCaptor.getValue(), 0, lengthCaptor.getValue(), StandardCharsets.UTF_8);
        assertEquals(2, countOccurrences(json, "\"strategyId\""));
    }

    @Test
    void testDoesNotFlushBeforeInterval() {
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);

        agent.onStart();
        mockTime = FLUSH_INTERVAL_MS - 1;
        agent.doWork();

        verify(registryConnection, org.mockito.Mockito.never())
                .post(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());
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
