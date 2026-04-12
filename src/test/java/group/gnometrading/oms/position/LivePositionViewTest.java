package group.gnometrading.oms.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LivePositionViewTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;

    private DefaultPositionTracker tracker;
    private PositionView view;

    @BeforeEach
    void setUp() {
        SharedPositionBuffer buffer = new SharedPositionBuffer(8);
        tracker = new DefaultPositionTracker(buffer);
        tracker.registerSlot(STRATEGY_ID, LISTING_ID);
        view = tracker.createPositionView(STRATEGY_ID);
    }

    @Test
    void getPosition_returnsCurrentBufferState() {
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 150, 2);

        Position pos = view.getPosition(LISTING_ID);

        assertEquals(10, pos.netQuantity);
        assertEquals(1500, pos.totalCost);
        assertEquals(2, pos.totalFees);
    }

    @Test
    void getPosition_reflectsSubsequentUpdates() {
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 10, 100, 0);
        tracker.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Ask, 4, 120, 0);

        Position pos = view.getPosition(LISTING_ID);

        assertEquals(6, pos.netQuantity);
        assertEquals(80, pos.realizedPnl); // 4 * (120 - 100)
    }

    @Test
    void getPosition_returnsFlyweightOnEveryCall() {
        Position first = view.getPosition(LISTING_ID);
        Position second = view.getPosition(LISTING_ID);

        assertSame(first, second);
    }

    @Test
    void getPosition_includesLeavesUpdates() {
        tracker.addStrategyLeaves(STRATEGY_ID, LISTING_ID, Side.Bid, 5);

        Position pos = view.getPosition(LISTING_ID);

        assertEquals(5, pos.leavesBuyQty);
    }
}
