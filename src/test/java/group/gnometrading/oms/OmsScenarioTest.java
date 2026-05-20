package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step-by-step scenario tests that drive specific edge-case sequences through the full OMS.
 * Each test asserts state after each step to pinpoint exactly where behavior diverges.
 */
class OmsScenarioTest {

    private OmsTestHarness h;

    @BeforeEach
    void setUp() {
        h = new OmsTestHarness();
    }

    /**
     * Fill races with cancel: strategy cancels, but exchange fills the order first.
     * The cancel arrives after the fill and should be a CANCEL_REJECT (or simply ignored).
     * The OMS must properly terminate the slot and clear leaves.
     */
    @Test
    void scenario_fillRacingWithCancel() {
        // Step 1: Submit bid, verify order out
        long counter = h.submitBidIntent(100L, 10L);
        assertEquals(1, h.sink.newOrders.size());
        h.sink.clear();

        // Step 2: Ack
        h.injectAck(counter, 10);
        assertEquals(0, h.sink.newOrders.size());

        // Step 3: Cancel intent sent
        h.submitCancelIntent();
        assertEquals(1, h.sink.cancels.size());
        h.sink.clear();

        // Step 4: FILL arrives before cancel reaches exchange (fill wins the race)
        h.injectFill(counter, 10, 100, 10, 0);

        // Slot must be EMPTY — order is filled
        assertNull(h.getTrackedOrder(counter));
        Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
        assertEquals(10L, pos.netQuantity);
        assertEquals(0L, pos.leavesBuyQty);

        // Step 5: Fresh intent works
        h.submitBidIntent(100L, 10L);
        assertEquals(1, h.sink.newOrders.size());
    }

    /**
     * Cancel-reject with a queued resubmit: strategy tries to cancel while order is live,
     * then queues a new intent. Exchange rejects the cancel. The queued intent should fire.
     */
    @Test
    void scenario_cancelRejectThenQueuedModify() {
        // Step 1: Submit bid, ack
        long counter = h.submitBidIntent(100L, 10L);
        h.injectAck(counter, 10);
        h.sink.clear();

        // Step 2: Cancel intent -> PENDING_CANCEL
        h.submitCancelIntent();
        assertEquals(1, h.sink.cancels.size());
        h.sink.clear();

        // Step 3: Queue a new intent at different price while pending
        h.submitBidIntent(101L, 10L); // queued
        assertEquals(0, h.sink.modifies.size()); // no action yet

        // Step 4: CANCEL_REJECT -> slot reverts to LIVE, queued intent fires as modify
        h.injectCancelReject(counter);
        assertEquals(1, h.sink.modifies.size());
        assertEquals(101L, h.sink.modifies.get(0).price());
    }

    /**
     * Multiple partial fills then a terminal fill: verify cumulative position tracking
     * is correct throughout.
     */
    @Test
    void scenario_multiplePartialFillsThenFill() {
        long counter = h.submitBidIntent(100L, 100L);
        h.injectAck(counter, 100);
        Position pos = h.getPosition(OmsTestHarness.LISTING_ID);

        // Step 1: partial fill 20
        h.injectFill(counter, 20, 100, 20, 80);
        assertEquals(20L, pos.netQuantity);
        assertEquals(80L, pos.leavesBuyQty);

        // Step 2: partial fill 30 more
        h.injectFill(counter, 30, 100, 50, 50);
        assertEquals(50L, pos.netQuantity);
        assertEquals(50L, pos.leavesBuyQty);

        // Step 3: terminal fill of remaining 50
        h.injectFill(counter, 50, 100, 100, 0);
        assertEquals(100L, pos.netQuantity);
        assertEquals(0L, pos.leavesBuyQty);

        assertNull(h.getTrackedOrder(counter));
    }

    /**
     * Partial fill arrives while the slot is PENDING_MODIFY.
     * The TrackedOrder state should update, position should update, but the slot
     * should remain PENDING_MODIFY until the modify ack arrives.
     */
    @Test
    void scenario_partialFillDuringPendingModify() {
        // Step 1: Submit bid size=10, ack
        long counter = h.submitBidIntent(100L, 10L);
        h.injectAck(counter, 10);

        // Step 2: Modify to 101@20 -> PENDING_MODIFY
        h.submitBidIntent(101L, 20L);
        assertEquals(1, h.sink.modifies.size());
        h.sink.clear();

        // Step 3: Partial fill 5 on original order while modify is in flight
        h.injectFill(counter, 5, 100, 5, 5);

        Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
        assertEquals(5L, pos.netQuantity);
        // leaves should reflect the partial fill removal
        // Modify already updated leaves to 20, partial fill removed 5 -> 15
        assertEquals(15L, pos.leavesBuyQty);
        assertEquals(0, h.sink.modifies.size()); // no second modify emitted

        // Step 4: Modify ack (NEW exec type confirms modify)
        h.injectAck(counter, 5); // ack for the modify
        // After ack, slot is LIVE at 101@20
        h.sink.clear();
        h.submitBidIntent(101L, 20L); // same price/size = no action
        assertEquals(0, h.sink.modifies.size());
    }

    /**
     * Synchronous rejection chain with risk: queued intent fires a resubmit that is also
     * synchronously rejected. The slot must end up EMPTY.
     */
    @Test
    void scenario_synchronousRejectionChain() {
        RiskEngine re = RiskEngine.withOrderPolicies(new MaxOrderSizePolicy(5));
        OmsTestHarness h2 = new OmsTestHarness(re);

        // Submit size=3 (passes risk)
        h2.submitBidIntent(100L, 3L);
        long counter = h2.sink.newOrders.get(0).clientOidCounter();

        // Queue intent size=10 (will fail risk when resubmitted)
        h2.submitBidIntent(102L, 10L);
        h2.sink.clear();

        // REJECT the first order: queued intent fires, submits size=10, gets rejected
        h2.injectReject(counter);

        // Slot must be EMPTY — a new intent with valid size works
        h2.submitBidIntent(100L, 5L);
        assertEquals(1, h2.sink.newOrders.size());
        assertEquals(5L, h2.sink.newOrders.get(0).size());
    }

    /**
     * Long-to-short position flip via fills.
     */
    @Test
    void scenario_longToShortFlip() {
        // Go long 10@100
        long bidCounter = h.submitBidIntent(100L, 10L);
        h.injectAck(bidCounter, 10);
        h.injectFill(bidCounter, 10, 100, 10, 0);

        Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
        assertEquals(10L, pos.netQuantity);
        assertEquals(10L * 100L, pos.totalCost);
        assertEquals(0L, pos.realizedPnl);

        // Go short 15@120: closes 10 long (realizes profit), opens 5 short
        long askCounter = h.submitAskIntent(120L, 15L);
        h.injectAck(askCounter, 15);
        h.injectFill(askCounter, 15, 120, 15, 0);

        assertEquals(-5L, pos.netQuantity);
        assertEquals(10L * (120L - 100L), pos.realizedPnl); // 200
        assertEquals(5L * 120L, pos.totalCost); // 5 short at 120
        assertEquals(0L, pos.leavesBuyQty);
        assertEquals(0L, pos.leavesSellQty);
    }

    /**
     * Take (IOC market) order bypasses the bid/ask slot mechanism and does not affect
     * the regular bid slot state.
     */
    @Test
    void scenario_takeOrderIndependentOfSlots() {
        // Submit a regular bid intent (slot PENDING_NEW)
        long bidCounter = h.submitBidIntent(100L, 10L);
        h.sink.clear();

        // Submit a take order (IOC market on bid side)
        group.gnometrading.schemas.Intent takeIntent = new group.gnometrading.schemas.Intent();
        takeIntent
                .encoder
                .strategyId(OmsTestHarness.STRATEGY_ID)
                .exchangeId(OmsTestHarness.EXCHANGE_ID)
                .securityId(OmsTestHarness.SECURITY_ID)
                .bidPrice(group.gnometrading.schemas.IntentDecoder.bidPriceNullValue())
                .bidSize(group.gnometrading.schemas.IntentDecoder.bidSizeNullValue())
                .askPrice(group.gnometrading.schemas.IntentDecoder.askPriceNullValue())
                .askSize(group.gnometrading.schemas.IntentDecoder.askSizeNullValue())
                .takeSize(5L)
                .takeSide(Side.Bid)
                .takeOrderType(group.gnometrading.schemas.OrderType.NULL_VAL);
        h.oms.processIntent(takeIntent, h.sink);

        // One IOC market order emitted
        assertEquals(1, h.sink.newOrders.size());
        assertEquals(
                group.gnometrading.schemas.TimeInForce.IMMEDIATE_OR_CANCELED,
                h.sink.newOrders.get(0).timeInForce());
        assertEquals(
                group.gnometrading.schemas.OrderType.MARKET,
                h.sink.newOrders.get(0).orderType());
        h.sink.clear();

        // The regular bid slot is still PENDING_NEW — ack fires no additional action
        h.injectAck(bidCounter, 10);
        assertEquals(0, h.sink.newOrders.size());
    }

    /**
     * Expire terminates the slot and resubmits any queued intent.
     */
    @Test
    void scenario_expireWithQueuedIntent_resubmits() {
        long counter = h.submitBidIntent(100L, 10L);
        h.injectAck(counter, 10);

        // Queue a new intent
        h.submitBidIntent(102L, 8L);
        // Cancel the first order via cancel intent to go PENDING_CANCEL
        // Actually let's queue while LIVE: slot was already acked, so submit different price
        // (modify queues are a different test; let's just submit after fill for expire)
        // Simpler: expire while PENDING_NEW
        OmsTestHarness h2 = new OmsTestHarness();
        long c = h2.submitBidIntent(100L, 10L);
        h2.submitBidIntent(102L, 8L); // queue intent
        h2.sink.clear();

        h2.injectExpire(c);

        // Queued intent resubmits
        assertEquals(1, h2.sink.newOrders.size());
        assertEquals(102L, h2.sink.newOrders.get(0).price());
        assertEquals(8L, h2.sink.newOrders.get(0).size());
    }
}
