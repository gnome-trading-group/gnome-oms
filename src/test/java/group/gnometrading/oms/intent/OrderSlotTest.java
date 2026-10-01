package group.gnometrading.oms.intent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderSlotTest {

    private OrderSlot slot;

    @BeforeEach
    void setUp() {
        slot = new OrderSlot();
    }

    // --- initial state ---

    @Test
    void startsEmpty() {
        assertEquals(OrderSlot.State.EMPTY, slot.getState());
        assertEquals(0, slot.getActiveClientOid());
        assertFalse(slot.hasQueuedIntent());
    }

    // --- EMPTY → PENDING_NEW → LIVE ---

    @Test
    void onNewSubmittedTransitionsToPendingNew() {
        slot.onNewSubmitted(42L, 100L, 10L, (short) 0);

        assertEquals(OrderSlot.State.PENDING_NEW, slot.getState());
        assertEquals(42L, slot.getActiveClientOid());
        assertEquals(100L, slot.getActivePrice());
        assertEquals(10L, slot.getActiveOrderQty());
    }

    @Test
    void onNewAckedTransitionsToLive() {
        slot.onNewSubmitted(1L, 100L, 10L, (short) 0);
        slot.onNewAcked();

        assertEquals(OrderSlot.State.LIVE, slot.getState());
    }

    // --- LIVE → PENDING_MODIFY → LIVE ---

    @Test
    void onModifySubmittedTransitionsToPendingModify() {
        goLive(1L, 100L, 10L);
        slot.onModifySubmitted(101L, 20L);

        assertEquals(OrderSlot.State.PENDING_MODIFY, slot.getState());
    }

    @Test
    void onModifyConfirmedUpdatesActivePriceAndSize() {
        goLive(1L, 100L, 10L);
        slot.onModifySubmitted(101L, 20L);
        slot.onModifyConfirmed();

        assertEquals(OrderSlot.State.LIVE, slot.getState());
        assertEquals(101L, slot.getActivePrice());
        assertEquals(20L, slot.getActiveOrderQty());
    }

    @Test
    void onModifyRejectedKeepsOriginalPriceAndSize() {
        goLive(1L, 100L, 10L);
        slot.onModifySubmitted(101L, 20L);
        slot.onModifyRejected();

        assertEquals(OrderSlot.State.LIVE, slot.getState());
        assertEquals(100L, slot.getActivePrice());
        assertEquals(10L, slot.getActiveOrderQty());
    }

    // --- LIVE → PENDING_CANCEL → EMPTY ---

    @Test
    void onCancelSubmittedTransitionsToPendingCancel() {
        goLive(1L, 100L, 10L);
        slot.onCancelSubmitted();

        assertEquals(OrderSlot.State.PENDING_CANCEL, slot.getState());
    }

    @Test
    void onTerminalResetsToEmpty() {
        goLive(1L, 100L, 10L);
        slot.onTerminal();

        assertEquals(OrderSlot.State.EMPTY, slot.getState());
        assertEquals(0L, slot.getActiveClientOid());
        assertEquals(0L, slot.getActivePrice());
        assertEquals(0L, slot.getActiveOrderQty());
    }

    // --- cancel reject ---

    @Test
    void onCancelRejectedRevertsToLive() {
        goLive(1L, 100L, 10L);
        slot.onCancelSubmitted();
        slot.onCancelRejected();

        assertEquals(OrderSlot.State.LIVE, slot.getState());
    }

    // --- queued intent ---

    @Test
    void queueIntentStoresValues() {
        slot.queueIntent(99L, 5L, (short) 0);

        assertTrue(slot.hasQueuedIntent());
        assertEquals(99L, slot.getQueuedPrice());
        assertEquals(5L, slot.getQueuedSize());
    }

    @Test
    void clearQueuedIntentResetsValues() {
        slot.queueIntent(99L, 5L, (short) 0);
        slot.clearQueuedIntent();

        assertFalse(slot.hasQueuedIntent());
        assertEquals(0L, slot.getQueuedPrice());
        assertEquals(0L, slot.getQueuedSize());
    }

    @Test
    void queueIntentOverwritesPreviousIntent() {
        slot.queueIntent(99L, 5L, (short) 0);
        slot.queueIntent(101L, 8L, (short) 0);

        assertEquals(101L, slot.getQueuedPrice());
        assertEquals(8L, slot.getQueuedSize());
    }

    // --- flags ---

    @Test
    void activeFlagsStoredOnNewSubmitted() {
        slot.onNewSubmitted(1L, 100L, 10L, (short) 1);

        assertEquals((short) 1, slot.getActiveFlags());
    }

    @Test
    void activeFlagsClearedOnTerminal() {
        slot.onNewSubmitted(1L, 100L, 10L, (short) 1);
        slot.onNewAcked();
        slot.onTerminal();

        assertEquals((short) 0, slot.getActiveFlags());
    }

    @Test
    void queuedFlagsStoredAndRetrieved() {
        slot.queueIntent(99L, 5L, (short) 1);

        assertEquals((short) 1, slot.getQueuedFlags());
    }

    @Test
    void queuedFlagsClearedOnClearQueuedIntent() {
        slot.queueIntent(99L, 5L, (short) 1);
        slot.clearQueuedIntent();

        assertEquals((short) 0, slot.getQueuedFlags());
    }

    // --- helpers ---

    private void goLive(long oid, long price, long size) {
        slot.onNewSubmitted(oid, price, size, (short) 0);
        slot.onNewAcked();
    }

    @Test
    void restingQty_IsOrderQtyLessFillsSeen() {
        final OrderSlot slot = new OrderSlot();
        slot.onNewSubmitted(1L, 100L, 10L, (short) 0);
        slot.onNewAcked();
        slot.onCumulativeQty(3L);

        assertEquals(10L, slot.getActiveOrderQty());
        assertEquals(7L, slot.getRestingQty());
        assertEquals(13L, slot.orderQtyForResting(10L)); // FIX: order qty includes what has filled
    }

    @Test
    void cumulativeQty_NeverRegresses() {
        final OrderSlot slot = new OrderSlot();
        slot.onNewSubmitted(1L, 100L, 10L, (short) 0);
        slot.onCumulativeQty(5L);
        slot.onCumulativeQty(3L); // a stale or replayed report

        assertEquals(5L, slot.getFilledQty());
    }

    @Test
    void confirmedModify_KeepsFillsAndAdoptsNewOrderQty() {
        final OrderSlot slot = new OrderSlot();
        slot.onNewSubmitted(1L, 100L, 10L, (short) 0);
        slot.onNewAcked();
        slot.onCumulativeQty(3L);
        slot.onModifySubmitted(101L, slot.orderQtyForResting(5L));
        slot.onModifyConfirmed();

        assertEquals(8L, slot.getActiveOrderQty());
        assertEquals(5L, slot.getRestingQty());
    }

    @Test
    void newSubmissionAndTerminal_ResetFills() {
        final OrderSlot slot = new OrderSlot();
        slot.onNewSubmitted(1L, 100L, 10L, (short) 0);
        slot.onCumulativeQty(4L);
        slot.onTerminal();
        assertEquals(0L, slot.getFilledQty());

        slot.onCumulativeQty(2L);
        slot.onNewSubmitted(2L, 100L, 10L, (short) 0);
        assertEquals(0L, slot.getFilledQty());
    }
}
