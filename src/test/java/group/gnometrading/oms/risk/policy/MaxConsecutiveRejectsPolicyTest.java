package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RejectStreaks;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;

class MaxConsecutiveRejectsPolicyTest {

    private static final int STRATEGY_ID = 1;
    private static final int KALSHI = 1;
    private static final int POLYMARKET = 2;
    private static final int LISTING = 100;
    private static final int OTHER_LISTING = 101;
    private static final int POLYMARKET_LISTING = 200;

    private final RejectStreaks streaks = new RejectStreaks();
    private final DefaultPositionTracker positions = new DefaultPositionTracker(new SharedPositionBuffer(4));
    private final PooledOrderStateManager orders = new PooledOrderStateManager(4);

    @Test
    void aStrategyLimitCountsRejectsAnywhere() {
        final MaxConsecutiveRejectsPolicy policy = new MaxConsecutiveRejectsPolicy(streaks, 0, 0, 3);
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        streaks.recordReject(STRATEGY_ID, POLYMARKET, POLYMARKET_LISTING);
        assertFalse(violated(policy, LISTING));

        streaks.recordReject(STRATEGY_ID, KALSHI, OTHER_LISTING);
        assertTrue(violated(policy, LISTING));
        assertTrue(violated(policy, POLYMARKET_LISTING));
        assertFalse(policy.isViolated(STRATEGY_ID + 1, LISTING, positions, orders), "another strategy's streak");
    }

    @Test
    void anExchangeLimitCountsOnlyThatExchangesRejects() {
        final MaxConsecutiveRejectsPolicy policy = new MaxConsecutiveRejectsPolicy(streaks, 0, KALSHI, 2);
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        streaks.recordReject(STRATEGY_ID, POLYMARKET, POLYMARKET_LISTING);
        assertFalse(violated(policy, LISTING));

        streaks.recordReject(STRATEGY_ID, KALSHI, OTHER_LISTING);
        assertTrue(violated(policy, LISTING));
    }

    @Test
    void aListingLimitCountsOnlyThatListingsRejects() {
        final MaxConsecutiveRejectsPolicy policy = new MaxConsecutiveRejectsPolicy(streaks, LISTING, KALSHI, 2);
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        streaks.recordReject(STRATEGY_ID, KALSHI, OTHER_LISTING);
        assertFalse(violated(policy, LISTING));
        assertFalse(violated(policy, OTHER_LISTING));

        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        assertTrue(violated(policy, LISTING));
    }

    @Test
    void anAcceptedOrderEndsEveryStreakCoveringIt() {
        final MaxConsecutiveRejectsPolicy policy = new MaxConsecutiveRejectsPolicy(streaks, 0, 0, 2);
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        streaks.recordReject(STRATEGY_ID, POLYMARKET, POLYMARKET_LISTING);
        streaks.reset(STRATEGY_ID, KALSHI, LISTING);

        assertFalse(violated(policy, LISTING));
        assertEquals(0, streaks.ofStrategy(STRATEGY_ID));
        assertEquals(0, streaks.ofExchange(STRATEGY_ID, KALSHI));
        assertEquals(1, streaks.ofExchange(STRATEGY_ID, POLYMARKET), "the other venue's own streak goes on");
        assertEquals(1, streaks.ofListing(STRATEGY_ID, POLYMARKET_LISTING));
    }

    @Test
    void aLatchEndsTheStreaksItCovers() {
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        streaks.recordReject(STRATEGY_ID, POLYMARKET, POLYMARKET_LISTING);
        streaks.recordReject(STRATEGY_ID + 1, KALSHI, LISTING);

        streaks.clear(STRATEGY_ID, KALSHI, 0);
        assertEquals(0, streaks.ofStrategy(STRATEGY_ID), "the strategy-wide streak counted the latched rejects");
        assertEquals(0, streaks.ofExchange(STRATEGY_ID, KALSHI));
        assertEquals(1, streaks.ofExchange(STRATEGY_ID, POLYMARKET));
        assertEquals(1, streaks.ofStrategy(STRATEGY_ID + 1));

        streaks.clear(STRATEGY_ID, 0, 0);
        assertEquals(0, streaks.ofExchange(STRATEGY_ID, POLYMARKET));
        assertEquals(0, streaks.ofListing(STRATEGY_ID, POLYMARKET_LISTING));
        assertEquals(1, streaks.ofListing(STRATEGY_ID + 1, LISTING));
    }

    @Test
    void reconfigureReadsMaxRejectsAndRefusesANonPositiveLimit() {
        final MaxConsecutiveRejectsPolicy policy = new MaxConsecutiveRejectsPolicy(streaks, 0, 0);
        policy.reconfigure(new ViewString("{\"maxRejects\": 1}"));
        assertFalse(violated(policy, LISTING));
        streaks.recordReject(STRATEGY_ID, KALSHI, LISTING);
        assertTrue(violated(policy, LISTING));

        assertThrows(IllegalArgumentException.class, () -> policy.reconfigure(new ViewString("{\"maxRejects\": 0}")));
    }

    private boolean violated(final MaxConsecutiveRejectsPolicy policy, final int listingId) {
        return policy.isViolated(STRATEGY_ID, listingId, positions, orders);
    }
}
