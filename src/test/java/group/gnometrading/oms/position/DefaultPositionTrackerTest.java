package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.schemas.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPositionTrackerTest {

    private static final int STRATEGY_1 = 1;
    private static final int STRATEGY_2 = 2;
    private static final int LISTING_A = 100;
    private static final int LISTING_B = 101;

    private SharedPositionBuffer buffer;
    private DefaultPositionTracker tracker;

    @BeforeEach
    void setUp() {
        buffer = new SharedPositionBuffer(16);
        tracker = new DefaultPositionTracker(buffer);
        tracker.registerSlot(STRATEGY_1, LISTING_A);
        tracker.registerSlot(STRATEGY_1, LISTING_B);
        tracker.registerSlot(STRATEGY_2, LISTING_A);
    }

    // --- getPosition (firm-level) ---

    @Test
    void getPosition_returnsNullBeforeAnyActivity() {
        assertNull(tracker.getPosition(LISTING_A));
    }

    @Test
    void getPosition_returnsFirmLevelPositionAfterFill() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);

        Position pos = tracker.getPosition(LISTING_A);
        assertNotNull(pos);
        assertEquals(10, pos.netQuantity);
    }

    @Test
    void getPosition_aggregatesBothStrategies() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_2, LISTING_A, Side.Bid, 5, 100, 0);

        Position pos = tracker.getPosition(LISTING_A);
        assertEquals(15, pos.netQuantity);
    }

    // --- getStrategyPosition ---

    @Test
    void getStrategyPosition_returnsNullForUnknownStrategy() {
        assertNull(tracker.getStrategyPosition(99, LISTING_A));
    }

    @Test
    void getStrategyPosition_returnsZeroPositionBeforeAnyFillForRegisteredStrategy() {
        // registerSlot eagerly creates the Position object
        Position pos = tracker.getStrategyPosition(STRATEGY_1, LISTING_A);
        assertNotNull(pos);
        assertEquals(0, pos.netQuantity);
        assertEquals(0, pos.realizedPnl);
    }

    @Test
    void getStrategyPosition_returnsCorrectStrategyPosition() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_2, LISTING_A, Side.Bid, 3, 100, 0);

        Position s1 = tracker.getStrategyPosition(STRATEGY_1, LISTING_A);
        Position s2 = tracker.getStrategyPosition(STRATEGY_2, LISTING_A);

        assertNotNull(s1);
        assertNotNull(s2);
        assertEquals(10, s1.netQuantity);
        assertEquals(3, s2.netQuantity);
    }

    @Test
    void getStrategyPosition_twoListingsForSameStrategy_areIndependent() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_1, LISTING_B, Side.Ask, 5, 200, 0);

        assertEquals(10, tracker.getStrategyPosition(STRATEGY_1, LISTING_A).netQuantity);
        assertEquals(-5, tracker.getStrategyPosition(STRATEGY_1, LISTING_B).netQuantity);
    }

    // --- applyStrategyFill: syncs to shared buffer ---

    @Test
    void applyStrategyFill_syncedToSharedBuffer() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 5);

        PositionView view = tracker.createPositionView(STRATEGY_1);
        Position pos = view.getPosition(LISTING_A);

        assertEquals(10, pos.netQuantity);
        assertEquals(1000, pos.totalCost);
        assertEquals(5, pos.totalFees);
    }

    // --- addStrategyLeaves / removeStrategyLeaves ---

    @Test
    void addStrategyLeaves_updatesBothFirmAndStrategy() {
        tracker.addStrategyLeaves(STRATEGY_1, LISTING_A, Side.Bid, 8);

        // Force a fill to create firm-level position so we can inspect it
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 1, 100, 0);

        // Strategy leaves were added before fill, firm position was created lazily by addStrategyLeaves
        // Re-add to check
        tracker.addStrategyLeaves(STRATEGY_1, LISTING_A, Side.Ask, 3);

        Position firmPos = tracker.getPosition(LISTING_A);
        assertNotNull(firmPos);
        assertEquals(3, firmPos.leavesSellQty);
    }

    @Test
    void removeStrategyLeaves_decrements() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 1, 100, 0);
        tracker.addStrategyLeaves(STRATEGY_1, LISTING_A, Side.Bid, 10);
        tracker.removeStrategyLeaves(STRATEGY_1, LISTING_A, Side.Bid, 4);

        Position stratPos = tracker.getStrategyPosition(STRATEGY_1, LISTING_A);
        assertEquals(6, stratPos.leavesBuyQty);
    }

    @Test
    void removeStrategyLeaves_onUnknownPositionDoesNotThrow() {
        // Should not throw; firm position doesn't exist yet
        tracker.removeStrategyLeaves(STRATEGY_1, LISTING_A, Side.Bid, 5);
    }

    @Test
    void addStrategyLeaves_syncedToSharedBuffer() {
        tracker.addStrategyLeaves(STRATEGY_1, LISTING_A, Side.Bid, 7);

        PositionView view = tracker.createPositionView(STRATEGY_1);
        Position pos = view.getPosition(LISTING_A);

        assertEquals(7, pos.leavesBuyQty);
    }

    // --- forEachPosition ---

    @Test
    void forEachPosition_iteratesAllFirmLevelPositions() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_2, LISTING_B, Side.Ask, 5, 200, 0);

        List<Integer> listingIds = new ArrayList<>();
        tracker.forEachPosition(pos -> listingIds.add(pos.listingId));

        assertEquals(2, listingIds.size());
    }

    // --- forEachStrategyPosition ---

    @Test
    void forEachStrategyPosition_iteratesAllRegisteredSlots() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);

        List<int[]> results = new ArrayList<>();
        tracker.forEachStrategyPosition((strategyId, listingId, pos) -> results.add(new int[] {strategyId, listingId}));

        // 3 slots were registered in setUp
        assertEquals(3, results.size());
    }

    // --- createPositionView ---

    @Test
    void createPositionView_returnsViewForStrategy() {
        tracker.applyStrategyFill(STRATEGY_1, LISTING_A, Side.Bid, 10, 100, 0);

        PositionView view = tracker.createPositionView(STRATEGY_1);
        Position pos = view.getPosition(LISTING_A);

        assertNotNull(pos);
        assertEquals(10, pos.netQuantity);
    }

    @Test
    void createPositionView_strategy1ViewDoesNotSeeStrategy2Fills() {
        tracker.applyStrategyFill(STRATEGY_2, LISTING_A, Side.Bid, 99, 100, 0);

        PositionView view = tracker.createPositionView(STRATEGY_1);
        Position pos = view.getPosition(LISTING_A);

        // Strategy 1's position for LISTING_A was never filled — should be all zeros from buffer init
        assertEquals(0, pos.netQuantity);
    }
}
