package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaxPnlLossPolicyTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;

    @Mock
    private OrderStateManager orders;

    private DefaultPositionTracker positions;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker();
    }

    private Position createPosition(double realizedPnl) {
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        final Position pos = positions.getStrategyPosition(STRATEGY_ID, LISTING_ID);
        pos.realizedPnl = realizedPnl;
        return pos;
    }

    @Test
    void testNotViolatedWhenNoPosition() {
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy(100.0);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testNotViolatedWhenPnlAboveNegativeMaxLoss() {
        createPosition(-50.0);
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy(100.0);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testNotViolatedWhenPnlExactlyAtNegativeMaxLoss() {
        createPosition(-100.0);
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy(100.0);
        // -100.0 < -100.0 is false — not violated
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testViolatedWhenPnlBelowNegativeMaxLoss() {
        createPosition(-100.01);
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy(100.0);
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testNotViolatedWhenPnlPositive() {
        createPosition(50.0);
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy(100.0);
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testReconfigureUpdatesMaxLoss() {
        createPosition(-51.0);
        final MaxPnlLossPolicy policy = new MaxPnlLossPolicy();
        policy.reconfigure(new ViewString("{\"maxLoss\": 50}"));
        assertTrue(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));

        policy.reconfigure(new ViewString("{\"maxLoss\": 1000}"));
        assertFalse(policy.isViolated(STRATEGY_ID, LISTING_ID, positions, orders));
    }
}
