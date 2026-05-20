package group.gnometrading.oms.intent;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentResolverTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 100;
    private static final int STRATEGY_ID = 7;

    private AtomicLong oidCounter;
    private IntentResolver resolver;
    private CapturingSink sink;

    @BeforeEach
    void setUp() {
        oidCounter = new AtomicLong(0);
        resolver = new IntentResolver(oidCounter::incrementAndGet, STRATEGY_ID);
        sink = new CapturingSink();
    }

    // --- resolve — EMPTY state ---

    @Test
    void emptySlotWithBidIntentSubmitsNewOrder() {
        resolve(100L, 10L, nullPrice(), 0L);

        assertEquals(1, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
        NewOrderCapture o = sink.newOrders.get(0);
        assertEquals(100L, o.price);
        assertEquals(10L, o.size);
        assertEquals(Side.Bid, o.side);
        assertEquals(OrderType.LIMIT, o.orderType);
        assertEquals(TimeInForce.GOOD_TILL_CANCELED, o.timeInForce);
    }

    @Test
    void emptySlotWithAskIntentSubmitsNewOrder() {
        resolve(nullPrice(), 0L, 101L, 5L);

        assertEquals(1, sink.newOrders.size());
        NewOrderCapture o = sink.newOrders.get(0);
        assertEquals(101L, o.price);
        assertEquals(5L, o.size);
        assertEquals(Side.Ask, o.side);
    }

    @Test
    void emptySlotWithBothSidesSubmitsTwoOrders() {
        resolve(100L, 10L, 101L, 5L);

        assertEquals(2, sink.newOrders.size());
    }

    @Test
    void emptySlotWithZeroSizeDoesNothing() {
        resolve(100L, 0L, nullPrice(), 0L);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    // --- resolve — PENDING_NEW state ---

    @Test
    void pendingNewQueuesIntentWithoutEmittingActions() {
        resolve(100L, 10L, nullPrice(), 0L);
        sink.clear();

        resolve(101L, 10L, nullPrice(), 0L);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    @Test
    void pendingNewQueuesCancelWithoutEmittingActions() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        sink.clear();

        resolve(nullPrice(), 0L, nullPrice(), 0L);

        assertEquals(0, sink.cancels.size());

        // cancel fires after ack
        ack(oid, Side.Bid);
        assertEquals(1, sink.cancels.size());
    }

    // --- resolve — LIVE state ---

    @Test
    void liveWithSamePriceAndSizeDoesNothing() {
        resolve(100L, 10L, nullPrice(), 0L);
        ack(sink.newOrders.get(0).clientOidCounter, Side.Bid);
        sink.clear();

        resolve(100L, 10L, nullPrice(), 0L);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    @Test
    void liveWithDifferentPriceEmitsModify() {
        resolve(100L, 10L, nullPrice(), 0L);
        ack(sink.newOrders.get(0).clientOidCounter, Side.Bid);
        sink.clear();

        resolve(101L, 10L, nullPrice(), 0L);

        assertEquals(1, sink.modifies.size());
        assertEquals(101L, sink.modifies.get(0).price);
    }

    @Test
    void liveWithDifferentSizeEmitsModify() {
        resolve(100L, 10L, nullPrice(), 0L);
        ack(sink.newOrders.get(0).clientOidCounter, Side.Bid);
        sink.clear();

        resolve(100L, 20L, nullPrice(), 0L);

        assertEquals(1, sink.modifies.size());
        assertEquals(20L, sink.modifies.get(0).size);
    }

    @Test
    void liveWithZeroSizeEmitsCancel() {
        resolve(100L, 10L, nullPrice(), 0L);
        ack(sink.newOrders.get(0).clientOidCounter, Side.Bid);
        sink.clear();

        resolve(nullPrice(), 0L, nullPrice(), 0L);

        assertEquals(1, sink.cancels.size());
    }

    // --- resolve — PENDING_MODIFY / PENDING_CANCEL state ---

    @Test
    void pendingModifyQueuesNewIntent() {
        goLive(100L, 10L);
        resolve(101L, 10L, nullPrice(), 0L); // emits modify
        sink.clear();

        resolve(102L, 10L, nullPrice(), 0L); // queued — no action

        assertEquals(0, sink.modifies.size());
    }

    @Test
    void pendingCancelQueuesNewIntent() {
        goLive(100L, 10L);
        resolve(nullPrice(), 0L, nullPrice(), 0L); // emits cancel
        sink.clear();

        resolve(101L, 10L, nullPrice(), 0L); // queued — no action

        assertEquals(0, sink.newOrders.size());
    }

    @Test
    void pendingModifyWithZeroSizeQueuesCancelNoImmediateAction() {
        goLive(100L, 10L);
        resolve(101L, 10L, nullPrice(), 0L); // into PENDING_MODIFY
        sink.clear();

        resolve(nullPrice(), 0L, nullPrice(), 0L); // queues cancel

        assertEquals(0, sink.cancels.size());
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void pendingModifyCancelFiresAfterModifyAck() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // PENDING_MODIFY
        resolve(nullPrice(), 0L, nullPrice(), 0L); // queue cancel
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.NEW, 101L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.cancels.size());
        assertEquals(0, sink.modifies.size());
    }

    // --- onExecutionReport — NEW ack ---

    @Test
    void newAckTransitionsToLive() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        sink.clear();

        ack(oid, Side.Bid);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    @Test
    void newAckWithQueuedModifyEmitsModifyNotCancel() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(101L, 10L, nullPrice(), 0L); // queue different price
        sink.clear();

        ack(oid, Side.Bid);

        assertEquals(0, sink.cancels.size());
        assertEquals(1, sink.modifies.size());
        assertEquals(101L, sink.modifies.get(0).price);
    }

    @Test
    void newAckWithQueuedCancelEmitsCancel() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(nullPrice(), 0L, nullPrice(), 0L); // queue cancel
        sink.clear();

        ack(oid, Side.Bid);

        assertEquals(1, sink.cancels.size());
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void newAckWithQueuedSamePriceAndSizeClearsQueueWithNoAction() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(100L, 10L, nullPrice(), 0L); // queue same values
        sink.clear();

        ack(oid, Side.Bid);

        assertEquals(0, sink.cancels.size());
        assertEquals(0, sink.modifies.size());
    }

    // --- onExecutionReport — NEW ack for a modify ---

    @Test
    void modifyAckConfirmsModify() {
        goLive(100L, 10L);
        resolve(101L, 20L, nullPrice(), 0L);
        long modifyOid = oidCounter.get(); // the oid used for modify is the same as new order
        sink.clear();

        // The modify ack arrives as ExecType.NEW with the original order's oid
        OrderExecutionReport report = buildReport(modifyOid - 1, ExecType.NEW, 101L, 20L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    // --- onExecutionReport — FILL ---

    @Test
    void fillTerminatesSlot() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.FILL, 100L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        // Slot is now EMPTY — a new intent triggers a new order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    // --- onExecutionReport — PARTIAL_FILL ---

    @Test
    void partialFillIsNoOp() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.PARTIAL_FILL, 100L, 5L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
    }

    // --- onExecutionReport — CANCEL ---

    @Test
    void cancelTerminatesSlot() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL, 100L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.newOrders.size());

        // Slot is EMPTY — next intent submits fresh order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    @Test
    void cancelWithQueuedIntentResubmits() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(102L, 8L, nullPrice(), 0L); // queue new intent
        sink.clear();

        // Before ack, simulate cancel
        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.newOrders.size());
        assertEquals(102L, sink.newOrders.get(0).price);
        assertEquals(8L, sink.newOrders.get(0).size);
    }

    @Test
    void cancelWithQueuedZeroSizeDoesNotResubmit() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(nullPrice(), 0L, nullPrice(), 0L); // queue cancel
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.newOrders.size());
    }

    // --- onExecutionReport — REJECT ---

    @Test
    void rejectTerminatesSlot() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.REJECT, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.newOrders.size());
    }

    @Test
    void rejectWithQueuedIntentResubmits() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(102L, 8L, nullPrice(), 0L);
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.REJECT, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.newOrders.size());
        assertEquals(102L, sink.newOrders.get(0).price);
    }

    // --- onExecutionReport — CANCEL_REJECT ---

    @Test
    void cancelRejectFromPendingCancelRevertsToLive() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(nullPrice(), 0L, nullPrice(), 0L); // triggers cancel, goes PENDING_CANCEL
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        // No actions and slot is back to LIVE — same intent does nothing
        assertEquals(0, sink.cancels.size());
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void cancelRejectWithQueuedModifyEmitsModify() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(nullPrice(), 0L, nullPrice(), 0L); // into PENDING_CANCEL
        resolve(101L, 10L, nullPrice(), 0L); // queue new intent
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.modifies.size());
        assertEquals(101L, sink.modifies.get(0).price);
    }

    @Test
    void cancelRejectWithNoQueueDoesNothing() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(nullPrice(), 0L, nullPrice(), 0L); // into PENDING_CANCEL
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.cancels.size());
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void modifyRejectRevertsToLive() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // PENDING_MODIFY
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.modifies.size());
        // Back to LIVE at original price — same intent does nothing
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void modifyRejectWithQueuedIntentEmitsModify() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // PENDING_MODIFY
        resolve(102L, 10L, nullPrice(), 0L); // queue another intent
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.modifies.size());
        assertEquals(102L, sink.modifies.get(0).price);
    }

    // --- synchronous rejection (handler rejects inside onNewOrder / onModify) ---
    // These tests verify the ordering fix: slot state is set BEFORE the handler is called,
    // so a synchronous rejection fired inside the handler finds the slot in the correct state.

    @Test
    void slotIsCleanedUpWhenHandlerRejectsSynchronously() {
        // Without the fix: slot is still EMPTY when the rejection fires → onExecutionReport returns early
        // → slot transitions to PENDING_NEW after the call → stuck forever.
        RejectingOnNewSink rejectingSink = new RejectingOnNewSink(resolver, sink);
        resolver.resolve(buildIntent(SECURITY_ID, 100L, 10L, nullPrice(), 0L), LISTING_ID, rejectingSink);
        sink.clear();

        // Slot must be EMPTY — a fresh intent submits a new order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    @Test
    void resubmittedOrderIsAlsoSynchronouslyRejectedAndSlotRemainsClean() {
        // First order pending, second intent queued
        resolve(100L, 10L, nullPrice(), 0L);
        long firstOid = sink.newOrders.get(0).clientOidCounter;
        resolve(102L, 8L, nullPrice(), 0L);
        sink.clear();

        // Handler rejects every new order synchronously — the original rejection causes the queued
        // intent to resubmit, which also gets synchronously rejected. Slot must be EMPTY at the end.
        RejectingOnNewSink rejectingSink = new RejectingOnNewSink(resolver, sink);
        OrderExecutionReport report = buildReport(firstOid, ExecType.REJECT, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, rejectingSink);

        // Slot must be EMPTY — a fresh resolve submits a new order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    @Test
    void slotRevertsToLiveWhenHandlerRejectsModifySynchronously() {
        // Without the fix: slot is still LIVE when the rejection fires → CANCEL_REJECT is ignored
        // → slot stays in PENDING_MODIFY after the call → stuck.
        goLive(100L, 10L);
        long oid = oidCounter.get();

        RejectingOnModifySink rejectingSink = new RejectingOnModifySink(resolver, sink, oid);
        resolver.resolve(buildIntent(SECURITY_ID, 101L, 10L, nullPrice(), 0L), LISTING_ID, rejectingSink);

        // Slot must have reverted to LIVE — same price/size intent does nothing
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(0, sink.modifies.size());
    }

    // --- onExecutionReport — stale / unknown ---

    @Test
    void staleReportIsIgnored() {
        goLive(100L, 10L);
        sink.clear();

        OrderExecutionReport report = buildReport(999L, ExecType.CANCEL, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.cancels.size());
    }

    @Test
    void reportForUnknownSecurityIsIgnored() {
        OrderExecutionReport report = buildReport(1L, ExecType.CANCEL, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, 999L, 999, report, Side.Bid, sink);

        assertEquals(0, sink.cancels.size());
    }

    // --- resolve — take orders ---

    @Test
    void takeIntentEmitsIocMarketOrder() {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(IntentDecoder.bidPriceNullValue())
                .bidSize(IntentDecoder.bidSizeNullValue())
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue())
                .takeSize(5L)
                .takeSide(Side.Bid)
                .takeOrderType(OrderType.NULL_VAL);

        resolver.resolve(intent, LISTING_ID, sink);

        assertEquals(1, sink.newOrders.size());
        NewOrderCapture o = sink.newOrders.get(0);
        assertEquals(5L, o.size);
        assertEquals(Side.Bid, o.side);
        assertEquals(OrderType.MARKET, o.orderType);
        assertEquals(TimeInForce.IMMEDIATE_OR_CANCELED, o.timeInForce);
    }

    @Test
    void takeWithLimitPriceUsesLimitOrderType() {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .bidPrice(IntentDecoder.bidPriceNullValue())
                .bidSize(IntentDecoder.bidSizeNullValue())
                .askPrice(IntentDecoder.askPriceNullValue())
                .askSize(IntentDecoder.askSizeNullValue())
                .takeSize(5L)
                .takeSide(Side.Ask)
                .takeOrderType(OrderType.LIMIT)
                .takeLimitPrice(102L);

        resolver.resolve(intent, LISTING_ID, sink);

        assertEquals(1, sink.newOrders.size());
        NewOrderCapture o = sink.newOrders.get(0);
        assertEquals(OrderType.LIMIT, o.orderType);
        assertEquals(102L, o.price);
        assertEquals(TimeInForce.IMMEDIATE_OR_CANCELED, o.timeInForce);
    }

    // --- multi-instrument ---

    @Test
    void separateSlotPerSecurity() {
        Intent intentA = buildIntent(SECURITY_ID, 100L, 10L, nullPrice(), 0L);
        Intent intentB = buildIntent(99L, 200L, 5L, nullPrice(), 0L);

        resolver.resolve(intentA, LISTING_ID, sink);
        resolver.resolve(intentB, 101, sink);

        assertEquals(2, sink.newOrders.size());

        // Ack order A and modify; order B should be unaffected
        long oidA = sink.newOrders.get(0).clientOidCounter;
        ackSecurity(oidA, SECURITY_ID, Side.Bid);
        sink.clear();

        resolve(101L, 10L, nullPrice(), 0L); // modifies security A
        assertEquals(1, sink.modifies.size());
    }

    @Test
    void sameSecurityDifferentListings_independentSlots() {
        int listingB = 200;
        // Same securityId, different listingIds (representing different exchanges)
        Intent intentA = buildIntent(SECURITY_ID, 100L, 10L, nullPrice(), 0L);
        Intent intentB = buildIntent(SECURITY_ID, 200L, 5L, nullPrice(), 0L);

        resolver.resolve(intentA, LISTING_ID, sink);
        resolver.resolve(intentB, listingB, sink);

        assertEquals(2, sink.newOrders.size()); // each listing gets its own slot

        // Ack listing A — listing B (still PENDING_NEW) must not be affected
        long oidA = sink.newOrders.get(0).clientOidCounter;
        ackSecurity(oidA, SECURITY_ID, Side.Bid);
        sink.clear();

        // Listing A is now LIVE; different price triggers modify
        resolver.resolve(buildIntent(SECURITY_ID, 101L, 10L, nullPrice(), 0L), LISTING_ID, sink);
        assertEquals(1, sink.modifies.size());
        assertEquals(101L, sink.modifies.get(0).price);

        // Listing B still PENDING_NEW — intent queues, no immediate action
        resolver.resolve(intentB, listingB, sink);
        assertEquals(1, sink.modifies.size()); // no extra modify from listing B
        assertEquals(0, sink.newOrders.size());
    }

    // --- additional edge cases ---

    @Test
    void cancelRejectWithQueuedCancel_emitsCancel() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(nullPrice(), 0L, nullPrice(), 0L); // slot -> PENDING_CANCEL
        resolve(nullPrice(), 0L, nullPrice(), 0L); // queue another cancel (size=0)
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.modifies.size());
        assertEquals(1, sink.cancels.size());
    }

    @Test
    void modifyRejectWithQueuedCancel_emitsCancel() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // slot -> PENDING_MODIFY
        resolve(nullPrice(), 0L, nullPrice(), 0L); // queue cancel (size=0)
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.modifies.size());
        assertEquals(1, sink.cancels.size());
    }

    @Test
    void fillDuringPendingModify_terminatesSlot() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // slot -> PENDING_MODIFY
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.FILL, 100L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        // Slot must be EMPTY — next intent triggers a fresh new order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    @Test
    void partialFillDuringPendingModify_doesNotChangeSlot() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(101L, 10L, nullPrice(), 0L); // slot -> PENDING_MODIFY
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.PARTIAL_FILL, 100L, 5L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(0, sink.newOrders.size());
        assertEquals(0, sink.modifies.size());
        assertEquals(0, sink.cancels.size());
        // Slot still PENDING_MODIFY — next intent queues
        resolve(102L, 10L, nullPrice(), 0L);
        assertEquals(0, sink.modifies.size());
    }

    @Test
    void fillDuringPendingCancel_terminatesSlotAndClearsQueuedIntent() {
        goLive(100L, 10L);
        long oid = oidCounter.get();
        resolve(nullPrice(), 0L, nullPrice(), 0L); // slot -> PENDING_CANCEL
        resolve(101L, 5L, nullPrice(), 0L); // queue new intent
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.FILL, 100L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        // FILL terminates slot; queued intent cleared because fill already consumed order
        assertEquals(0, sink.newOrders.size());
        // Slot EMPTY — next intent submits fresh order
        resolve(100L, 10L, nullPrice(), 0L);
        assertEquals(1, sink.newOrders.size());
    }

    @Test
    void rapidIntentChanges_onlyLastOneQueued() {
        resolve(100L, 10L, nullPrice(), 0L); // slot -> PENDING_NEW
        // Submit 5 intents — each overwrites the previous queued intent
        resolve(101L, 11L, nullPrice(), 0L);
        resolve(102L, 12L, nullPrice(), 0L);
        resolve(103L, 13L, nullPrice(), 0L);
        resolve(104L, 14L, nullPrice(), 0L);
        resolve(105L, 15L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        sink.clear();

        ack(oid, Side.Bid);

        // Only the last queued intent (105@15) should fire as a modify
        assertEquals(1, sink.modifies.size());
        assertEquals(105L, sink.modifies.get(0).price);
        assertEquals(15L, sink.modifies.get(0).size);
    }

    @Test
    void fillClearsQueuedIntent_noResubmit() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(101L, 10L, nullPrice(), 0L); // queue intent
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.FILL, 100L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        // FILL clears queued intent without resubmitting
        assertEquals(0, sink.newOrders.size());
    }

    @Test
    void expireWithQueuedIntent_resubmits() {
        resolve(100L, 10L, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        resolve(102L, 8L, nullPrice(), 0L);
        sink.clear();

        OrderExecutionReport report = buildReport(oid, ExecType.EXPIRE, 0L, 0L);
        resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, sink);

        assertEquals(1, sink.newOrders.size());
        assertEquals(102L, sink.newOrders.get(0).price);
        assertEquals(8L, sink.newOrders.get(0).size);
    }

    // --- helpers ---

    private void resolve(long bidPrice, long bidSize, long askPrice, long askSize) {
        resolver.resolve(buildIntent(SECURITY_ID, bidPrice, bidSize, askPrice, askSize), LISTING_ID, sink);
    }

    private Intent buildIntent(long securityId, long bidPrice, long bidSize, long askPrice, long askSize) {
        Intent intent = new Intent();
        intent.encoder
                .strategyId(STRATEGY_ID)
                .exchangeId(EXCHANGE_ID)
                .securityId(securityId)
                .bidPrice(bidPrice)
                .bidSize(bidSize == 0 ? IntentDecoder.bidSizeNullValue() : bidSize)
                .askPrice(askPrice)
                .askSize(askSize == 0 ? IntentDecoder.askSizeNullValue() : askSize)
                .takeSize(IntentDecoder.takeSizeNullValue());
        return intent;
    }

    private void ack(long clientOidCounter, Side side) {
        ackSecurity(clientOidCounter, SECURITY_ID, side);
    }

    private void ackSecurity(long clientOidCounter, long securityId, Side side) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, STRATEGY_ID);
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId((int) securityId)
                .execType(ExecType.NEW)
                .filledQty(0)
                .fillPrice(0)
                .cumulativeQty(0)
                .leavesQty(10);
        resolver.onExecutionReport(EXCHANGE_ID, securityId, LISTING_ID, report, side, sink);
    }

    private OrderExecutionReport buildReport(long clientOidCounter, ExecType type, long price, long leavesQty) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOidCounter, STRATEGY_ID);
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(type)
                .filledQty(0)
                .fillPrice(price)
                .cumulativeQty(0)
                .leavesQty(leavesQty);
        return report;
    }

    private void goLive(long bidPrice, long bidSize) {
        resolve(bidPrice, bidSize, nullPrice(), 0L);
        long oid = sink.newOrders.get(0).clientOidCounter;
        sink.clear();
        ack(oid, Side.Bid);
        sink.clear();
    }

    private static long nullPrice() {
        return IntentDecoder.bidPriceNullValue();
    }

    // --- capturing sink ---

    private static final class CapturingSink implements ActionSink {
        final List<NewOrderCapture> newOrders = new ArrayList<>();
        final List<ModifyCapture> modifies = new ArrayList<>();
        final List<Long> cancels = new ArrayList<>();

        void clear() {
            newOrders.clear();
            modifies.clear();
            cancels.clear();
        }

        @Override
        public void onNewOrder(Order order) {
            newOrders.add(new NewOrderCapture(
                    order.getClientOidCounter(),
                    order.decoder.price(),
                    order.decoder.size(),
                    order.decoder.side(),
                    order.decoder.orderType(),
                    order.decoder.timeInForce()));
        }

        @Override
        public void onModify(ModifyOrder modify) {
            modifies.add(new ModifyCapture(modify.decoder.price(), modify.decoder.size()));
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            cancels.add(cancel.getClientOidCounter());
        }
    }

    private record NewOrderCapture(
            long clientOidCounter, long price, long size, Side side, OrderType orderType, TimeInForce timeInForce) {}

    private record ModifyCapture(long price, long size) {}

    /** Fires a synchronous REJECT exec report back into the resolver when a new order is received. */
    private final class RejectingOnNewSink implements ActionSink {
        private final IntentResolver resolver;
        private final CapturingSink delegate;

        RejectingOnNewSink(IntentResolver resolver, CapturingSink delegate) {
            this.resolver = resolver;
            this.delegate = delegate;
        }

        @Override
        public void onNewOrder(Order order) {
            OrderExecutionReport report = buildReport(order.getClientOidCounter(), ExecType.REJECT, 0L, 0L);
            resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, order.decoder.side(), delegate);
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            delegate.onCancel(cancel);
        }

        @Override
        public void onModify(ModifyOrder modify) {
            delegate.onModify(modify);
        }
    }

    /** Fires a synchronous CANCEL_REJECT exec report back into the resolver when a modify is received. */
    private final class RejectingOnModifySink implements ActionSink {
        private final IntentResolver resolver;
        private final CapturingSink delegate;
        private final long oid;

        RejectingOnModifySink(IntentResolver resolver, CapturingSink delegate, long oid) {
            this.resolver = resolver;
            this.delegate = delegate;
            this.oid = oid;
        }

        @Override
        public void onNewOrder(Order order) {
            delegate.onNewOrder(order);
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            delegate.onCancel(cancel);
        }

        @Override
        public void onModify(ModifyOrder modify) {
            OrderExecutionReport report = buildReport(oid, ExecType.CANCEL_REJECT, 100L, 10L);
            resolver.onExecutionReport(EXCHANGE_ID, SECURITY_ID, LISTING_ID, report, Side.Bid, delegate);
        }
    }
}
