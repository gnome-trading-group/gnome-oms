package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.OmsTestHarness;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.TestListings;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxCommittedCapitalPolicyTest {

    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;
    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long CENT = DOLLAR / 100;
    private static final int STRATEGY_ID = 1;
    private static final int KALSHI = 1;
    private static final int HYPERLIQUID = 2;
    private static final int BINARY = 100;
    private static final int OTHER_BINARY = 101;
    private static final int PERP = 200;

    private final ListingDirectory directory = TestListings.directory(
            TestListings.binary(BINARY, KALSHI),
            TestListings.binary(OTHER_BINARY, KALSHI),
            TestListings.perpetual(PERP, HYPERLIQUID));

    private DefaultPositionTracker positions;
    private PooledOrderStateManager orders;
    private long nextCounter = 1;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        orders = new PooledOrderStateManager(16);
    }

    @Test
    void aBuyIsJudgedWithEverythingAlreadyWorking() {
        final MaxCommittedCapitalPolicy policy = strategyWide(10 * DOLLAR);
        assertFalse(violates(policy, BINARY, Side.Bid, 40 * CENT, 20 * UNIT), "$8");
        assertTrue(violates(policy, BINARY, Side.Bid, 40 * CENT, 30 * UNIT), "$12");

        work(BINARY, Side.Bid, 40 * CENT, 20 * UNIT);
        assertTrue(violates(policy, BINARY, Side.Bid, 30 * CENT, 10 * UNIT), "$8 working and $3 more");
        assertFalse(violates(policy, BINARY, Side.Bid, 20 * CENT, 10 * UNIT), "$8 working and $2 more");
    }

    @Test
    void aBinarySellCommitsADollarLessItsPrice() {
        final MaxCommittedCapitalPolicy policy = strategyWide(10 * DOLLAR);
        assertFalse(violates(policy, BINARY, Side.Ask, 80 * CENT, 50 * UNIT), "$10");
        assertTrue(violates(policy, BINARY, Side.Ask, 70 * CENT, 50 * UNIT), "$15");
    }

    @Test
    void anOrderThatOnlyClosesPassesEvenOverTheLimit() {
        final MaxCommittedCapitalPolicy policy = strategyWide(10 * DOLLAR);
        positions.applyStrategyFill(STRATEGY_ID, BINARY, Side.Bid, 100 * UNIT, 40 * CENT, 0);

        assertFalse(violates(policy, BINARY, Side.Ask, 50 * CENT, 100 * UNIT), "closing commits nothing more");
        assertTrue(violates(policy, BINARY, Side.Ask, 50 * CENT, 101 * UNIT), "one more would open a short");
        assertTrue(violates(policy, BINARY, Side.Bid, 1 * CENT, 1 * UNIT));
    }

    @Test
    void aModifyIsJudgedAsTheReplacementForItsOrder() {
        final MaxCommittedCapitalPolicy policy = strategyWide(5 * DOLLAR);
        final TrackedOrder working = work(BINARY, Side.Bid, 40 * CENT, 10 * UNIT);

        assertTrue(violates(policy, modifyOf(working, 40 * CENT, 20 * UNIT)), "$8 replaces $4");
        assertFalse(violates(policy, modifyOf(working, 40 * CENT, 12 * UNIT)), "$4.80 replaces $4");
        assertFalse(violates(policy, modifyOf(working, 30 * CENT, 5 * UNIT)), "shrinking always passes");
    }

    @Test
    void aShrinkingModifyPassesWhileOverTheLimit() {
        final MaxCommittedCapitalPolicy policy = strategyWide(DOLLAR);
        final TrackedOrder working = work(BINARY, Side.Bid, 40 * CENT, 10 * UNIT);

        assertFalse(violates(policy, modifyOf(working, 40 * CENT, 5 * UNIT)));
        assertTrue(violates(policy, modifyOf(working, 41 * CENT, 10 * UNIT)));
    }

    @Test
    void aNonBinaryListingCountsGrossNotional() {
        final MaxCommittedCapitalPolicy policy = strategyWide(1_000 * DOLLAR);
        positions.applyStrategyFill(STRATEGY_ID, PERP, Side.Ask, 5 * UNIT, 100 * DOLLAR, 0);

        assertFalse(violates(policy, PERP, Side.Ask, 100 * DOLLAR, 5 * UNIT), "$500 short and $500 more");
        assertTrue(violates(policy, PERP, Side.Ask, 100 * DOLLAR, 6 * UNIT));
    }

    @Test
    void anExchangeLimitCountsOnlyThatExchangesListings() {
        final MaxCommittedCapitalPolicy onKalshi = new MaxCommittedCapitalPolicy(directory, 0, KALSHI, 10 * DOLLAR);
        positions.applyStrategyFill(STRATEGY_ID, PERP, Side.Bid, 1 * UNIT, 100 * DOLLAR, 0);
        positions.applyStrategyFill(STRATEGY_ID, OTHER_BINARY, Side.Bid, 10 * UNIT, 60 * CENT, 0);

        assertFalse(violates(onKalshi, BINARY, Side.Bid, 30 * CENT, 10 * UNIT), "$6 on Kalshi and $3 more");
        assertTrue(violates(onKalshi, BINARY, Side.Bid, 50 * CENT, 10 * UNIT), "$6 on Kalshi and $5 more");
        assertTrue(violates(strategyWide(10 * DOLLAR), BINARY, Side.Bid, 30 * CENT, 10 * UNIT), "the $100 perp too");
    }

    @Test
    void aListingLimitCountsOnlyThatListing() {
        final MaxCommittedCapitalPolicy onListing = new MaxCommittedCapitalPolicy(directory, BINARY, 0, 10 * DOLLAR);
        positions.applyStrategyFill(STRATEGY_ID, OTHER_BINARY, Side.Bid, 100 * UNIT, 60 * CENT, 0);

        assertFalse(violates(onListing, BINARY, Side.Bid, 50 * CENT, 20 * UNIT));
        assertTrue(violates(onListing, BINARY, Side.Bid, 50 * CENT, 21 * UNIT));
    }

    @Test
    void reconfigureReadsMaxCapital() {
        final MaxCommittedCapitalPolicy policy = new MaxCommittedCapitalPolicy(directory, 0, 0);
        policy.reconfigure(new ViewString("{\"maxCapital\": " + 5 * DOLLAR + "}"));
        assertFalse(violates(policy, BINARY, Side.Bid, 50 * CENT, 10 * UNIT));
        assertTrue(violates(policy, BINARY, Side.Bid, 50 * CENT, 11 * UNIT));
    }

    private MaxCommittedCapitalPolicy strategyWide(final long maxCapital) {
        return new MaxCommittedCapitalPolicy(directory, 0, 0, maxCapital);
    }

    private boolean violates(
            final MaxCommittedCapitalPolicy policy,
            final int listingId,
            final Side side,
            final long price,
            final long size) {
        return violates(policy, order(listingId, nextCounter++, side, price, size));
    }

    private boolean violates(final MaxCommittedCapitalPolicy policy, final Order order) {
        return policy.isViolated(STRATEGY_ID, listingOf(order), order, positions, orders);
    }

    private static int listingOf(final Order order) {
        return (int) order.decoder.securityId();
    }

    /** An order the OMS has accepted and is working, its leaves on the position as the OMS keeps them. */
    private TrackedOrder work(final int listingId, final Side side, final long price, final long size) {
        final TrackedOrder tracked = orders.trackOrder(order(listingId, nextCounter++, side, price, size));
        positions.adjustStrategyLeaves(STRATEGY_ID, listingId, side, tracked.workingQty(), tracked.workingNotional());
        return tracked;
    }

    /** What the OMS checks for a modify: the order's id with the new price and what can still execute. */
    private static Order modifyOf(final TrackedOrder working, final long price, final long newOrderQty) {
        return order(
                (int) working.getSecurityId(),
                working.getClientOidCounter(),
                working.getSide(),
                price,
                Math.max(0, newOrderQty - working.getFilledQty()));
    }

    // The security id doubles as the listing id, so a check knows which listing an order is on.
    private static Order order(
            final int listingId, final long counter, final Side side, final long price, final long size) {
        return OmsTestHarness.buildOrder(STRATEGY_ID, counter, 1, listingId, side, price, size);
    }
}
