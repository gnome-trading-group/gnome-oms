package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSnapshots;
import group.gnometrading.oms.risk.policy.MaxPnlLossPolicy;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Kill switches through the real OMS: what gets cancelled, what is blocked, and what is never re-placed. */
class KillSwitchScenarioTest {

    private static final int OTHER_STRATEGY = 8;
    private static final int OTHER_SECURITY = 43;
    private static final int OTHER_LISTING = 101;
    private static final int[] NONE = {};
    private static final long PX = Statics.SIZE_SCALING_FACTOR;

    private RiskEngine engine;
    private OmsTestHarness h;

    @BeforeEach
    void setUp() {
        engine = new RiskEngine();
        h = new OmsTestHarness(engine);
        h.stubListing(OmsTestHarness.EXCHANGE_ID, OTHER_SECURITY, OTHER_LISTING, 0, 0);
    }

    @Test
    void globalKillCancelsEveryRestingOrderOnce() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        long ask = ackedAsk(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY);
        long otherStrategy = ackedBid(OTHER_STRATEGY, OmsTestHarness.SECURITY_ID);
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.applyRiskChanges();

        assertEquals(3, h.sink.cancels.size(), "each resting order cancelled exactly once");
        assertTrue(h.sink.cancels.contains(bid));
        assertTrue(h.sink.cancels.contains(ask));
        assertTrue(h.sink.cancels.contains(otherStrategy));
    }

    @Test
    void killedScopeIgnoresTheStrategysIntents() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.sink.clear();

        // The strategy keeps quoting while killed; none of it may reach the venue.
        h.submitBidIntent(101L * PX, 10L);
        h.submitTakeIntent(5, Side.Bid);
        h.injectCancel(bid);

        assertEquals(0, h.sink.newOrders.size());
        assertEquals(0, h.sink.modifies.size());
        assertEquals(0, h.sink.cancels.size());
    }

    @Test
    void anOrderNotYetAcknowledgedIsCancelledWhenItsAckArrives() {
        long pending = h.submitBidIntent(100L * PX, 10L);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        assertEquals(0, h.sink.cancels.size(), "nothing to address until the venue acknowledges it");

        h.injectAck(pending, 10);

        assertEquals(1, h.sink.cancels.size());
        assertEquals(pending, h.sink.cancels.get(0));
    }

    @Test
    void strategyKillCancelsOnlyThatStrategy() {
        long mine = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        ackedBid(OTHER_STRATEGY, OmsTestHarness.SECURITY_ID);
        h.sink.clear();

        RiskSnapshots.publishKills(engine, false, new int[] {OmsTestHarness.STRATEGY_ID}, NONE);
        h.applyRiskChanges();

        assertEquals(1, h.sink.cancels.size());
        assertEquals(List.of(mine), h.sink.cancels);
        h.submitBidIntent(OTHER_STRATEGY, OTHER_SECURITY, 100L * PX, 10L);
        assertEquals(1, h.sink.newOrders.size(), "the other strategy keeps trading");
    }

    @Test
    void listingKillCancelsOnlyThatListing() {
        long onKilled = ackedBid(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY);
        ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        h.sink.clear();

        RiskSnapshots.publishKills(engine, false, NONE, new int[] {OTHER_LISTING});
        h.applyRiskChanges();

        assertEquals(1, h.sink.cancels.size());
        assertEquals(List.of(onKilled), h.sink.cancels);
    }

    @Test
    void resumingLetsIntentsThroughWithoutCancellingAgain() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.injectCancel(bid);
        h.sink.clear();

        RiskSnapshots.publishNoKills(engine);
        h.applyRiskChanges();
        h.submitBidIntent(100L * PX, 10L);

        assertEquals(0, h.sink.cancels.size());
        assertEquals(1, h.sink.newOrders.size());
    }

    @Test
    void manyOpenOrdersAreAllCancelled() {
        final int strategies = 30; // the harness tracks at most 64 orders at once
        for (int strategyId = 1; strategyId <= strategies; strategyId++) {
            ackedBid(strategyId, OmsTestHarness.SECURITY_ID);
            ackedAsk(strategyId, OTHER_SECURITY);
        }
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();

        assertEquals(2 * strategies, h.sink.cancels.size());
    }

    @Test
    void marketBreachCancelsOnceLatchesAndEscalatesThenTheRegistryHoldsIt() {
        engine = RiskEngine.withPolicies(new OrderRiskPolicy[] {}, new MarketRiskPolicy[] {new MaxPnlLossPolicy(100L)});
        h = new OmsTestHarness(engine);
        h.stubListing(OmsTestHarness.EXCHANGE_ID, OTHER_SECURITY, OTHER_LISTING, 0, 0);
        long resting = ackedBid(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY);

        long buy = h.submitBidIntent(200L * PX, 10L);
        h.injectAck(buy, 10);
        h.injectFill(buy, 10, 200 * PX, 10, 0);
        long sell = h.submitAskIntent(180L * PX, 10L);
        h.injectAck(sell, 10);
        h.sink.clear();
        h.injectFill(sell, 10, 180 * PX, 10, 0); // a loss of 200 against a limit of 100

        assertTrue(engine.isLatched(OmsTestHarness.STRATEGY_ID));
        assertEquals(1, h.sink.cancels.size());
        assertEquals(resting, h.sink.cancels.get(0));
        assertEquals(List.of(OmsTestHarness.STRATEGY_ID), RiskSnapshots.drainLatchedHalts(engine));

        // The registry now records the halt as a strategy kill; the latch hands over to it.
        RiskSnapshots.publishKills(engine, false, new int[] {OmsTestHarness.STRATEGY_ID}, NONE);
        h.applyRiskChanges();
        assertFalse(engine.isLatched(OmsTestHarness.STRATEGY_ID), "the registry's kill now holds the halt");
        assertTrue(engine.isBlocked(OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID));
        assertEquals(1, h.sink.cancels.size(), "the resting order's cancel is still pending; nothing new");

        // An operator resumes it.
        RiskSnapshots.publishNoKills(engine);
        h.applyRiskChanges();
        assertFalse(engine.isBlocked(OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID));
    }

    @Test
    void aCancelTheVenueRefusesIsSentAgainOnTheNextSweep() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.injectCancelReject(bid); // e.g. rate-limited by a burst of kill cancels
        h.sink.clear();

        h.applyRiskChanges();
        assertEquals(0, h.sink.cancels.size(), "not before the sweep interval");

        h.advanceNanos(1_000_000_001L);
        h.applyRiskChanges();
        assertEquals(List.of(bid), h.sink.cancels);
    }

    @Test
    void aReplacementQueuedBehindACancelIsNotPlacedOnceKilled() {
        h.stubListing(
                OmsTestHarness.EXCHANGE_ID,
                OTHER_SECURITY,
                OTHER_LISTING,
                0,
                0,
                OmsTestHarness.CANCEL_REPLACE_EXCHANGE_CODE);
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY);
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 101L * PX, 10L); // cancel, then replace
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        injectCancel(OmsTestHarness.STRATEGY_ID, bid, OTHER_SECURITY);

        assertEquals(0, h.sink.newOrders.size());
        assertEquals(0, h.sink.cancels.size(), "its cancel was already in flight");
    }

    @Test
    void aModifyInFlightIsCancelledWhenItsAckArrives() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        h.submitBidIntent(101L * PX, 10L); // native modify
        assertEquals(1, h.sink.modifies.size());
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        assertEquals(0, h.sink.cancels.size());
        h.injectAck(bid, 10);

        assertEquals(List.of(bid), h.sink.cancels);
    }

    @Test
    void aTakeOrderIsCancelledOncePerSweepNotOnEveryPass() {
        long take = h.submitTakeIntent(5, Side.Bid);
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.applyRiskChanges();
        h.applyRiskChanges();

        assertEquals(List.of(take), h.sink.cancels);
    }

    @Test
    void staleRiskRejectsNewOrdersButLetsTheStrategyPullItsQuote() {
        final long[] nowMs = {1_000};
        engine = RiskEngine.syncedFromRegistry(() -> nowMs[0], Duration.ofSeconds(30));
        h = new OmsTestHarness(engine);
        RiskSnapshots.recordRefresh(engine, nowMs[0]);
        h.applyRiskChanges();
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);

        nowMs[0] += 31_000;
        h.applyRiskChanges();
        h.sink.clear();
        h.submitBidIntent(100L * PX, 0L); // pull the quote
        h.submitAskIntent(110L * PX, 10L); // and try something new

        assertEquals(List.of(bid), h.sink.cancels, "the strategy can still cancel");
        assertEquals(0, h.sink.newOrders.size(), "nothing new while the policies are unknown");
    }

    @Test
    void aLatchedStrategyIsSweptAgainUntilItsOrdersAreGone() {
        engine = RiskEngine.withPolicies(new OrderRiskPolicy[] {}, new MarketRiskPolicy[] {new MaxPnlLossPolicy(100L)});
        h = new OmsTestHarness(engine);
        h.stubListing(OmsTestHarness.EXCHANGE_ID, OTHER_SECURITY, OTHER_LISTING, 0, 0);
        long resting = ackedBid(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY);
        long buy = h.submitBidIntent(200L * PX, 10L);
        h.injectAck(buy, 10);
        h.injectFill(buy, 10, 200 * PX, 10, 0);
        long sell = h.submitAskIntent(180L * PX, 10L);
        h.injectAck(sell, 10);
        h.injectFill(sell, 10, 180 * PX, 10, 0); // breach: latched, resting order cancelled
        h.applyRiskChanges();
        injectCancelReject(OmsTestHarness.STRATEGY_ID, resting, OTHER_SECURITY);
        h.sink.clear();

        h.advanceNanos(1_000_000_001L);
        h.applyRiskChanges();

        assertEquals(List.of(resting), h.sink.cancels);
    }

    @Test
    void aKillLiftedAndAppliedAgainCancelsWhatWasPlacedInBetween() {
        long first = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.injectCancel(first);
        RiskSnapshots.publishNoKills(engine);
        h.applyRiskChanges();
        long second = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        h.sink.clear();

        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();

        assertEquals(List.of(second), h.sink.cancels);
    }

    @Test
    void anOrderWhoseAckNeverArrivesIsCancelledDirectlyOnTheNextSweep() {
        long unacked = h.submitBidIntent(100L * PX, 10L);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        assertEquals(0, h.sink.cancels.size(), "the first sweep waits for the ack");

        h.advanceNanos(1_000_000_001L);
        h.applyRiskChanges();

        assertEquals(List.of(unacked), h.sink.cancels);
    }

    @Test
    void aModifyInFlightIsNotCancelledDirectlyOnAResweep() {
        long bid = ackedBid(OmsTestHarness.STRATEGY_ID, OmsTestHarness.SECURITY_ID);
        h.submitBidIntent(101L * PX, 20L); // native modify, never answered
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.advanceNanos(1_000_000_001L);
        h.sink.clear();

        h.applyRiskChanges();
        assertEquals(0, h.sink.cancels.size(), "a refused cancel would read as the modify's refusal");

        h.injectAck(bid, 20);
        assertEquals(List.of(bid), h.sink.cancels, "cancelled as soon as the modify is answered");
    }

    @Test
    void aTakeOrderStillOpenIsCancelledAgainOnTheNextSweep() {
        long take = h.submitTakeIntent(5, Side.Bid);
        RiskSnapshots.publishKills(engine, true, NONE, NONE);
        h.applyRiskChanges();
        h.advanceNanos(1_000_000_001L);
        h.applyRiskChanges();

        assertEquals(List.of(take, take), h.sink.cancels);
    }

    private void injectCancelReject(final int strategyId, final long counter, final long securityId) {
        h.injectExecReport(
                strategyId,
                counter,
                OmsTestHarness.EXCHANGE_ID,
                (int) securityId,
                ExecType.CANCEL_REJECT,
                0,
                0,
                0,
                0,
                OrderExecutionReportDecoder.feeNullValue());
    }

    private void injectCancel(final int strategyId, final long counter, final long securityId) {
        h.injectExecReport(
                strategyId,
                counter,
                OmsTestHarness.EXCHANGE_ID,
                (int) securityId,
                ExecType.CANCEL,
                0,
                0,
                0,
                0,
                OrderExecutionReportDecoder.feeNullValue());
    }

    private long ackedBid(final int strategyId, final long securityId) {
        long counter = h.submitBidIntent(strategyId, securityId, 100L * PX, 10L);
        ack(strategyId, counter, securityId);
        return counter;
    }

    private long ackedAsk(final int strategyId, final long securityId) {
        long counter = h.submitAskIntent(strategyId, securityId, 110L * PX, 10L);
        ack(strategyId, counter, securityId);
        return counter;
    }

    private void ack(final int strategyId, final long counter, final long securityId) {
        h.injectExecReport(
                strategyId,
                counter,
                OmsTestHarness.EXCHANGE_ID,
                (int) securityId,
                ExecType.NEW,
                0,
                0,
                0,
                10,
                OrderExecutionReportDecoder.feeNullValue());
    }
}
