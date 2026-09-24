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
        assertEquals(10L, slot.getActiveSize());
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
        assertEquals(20L, slot.getActiveSize());
    }

    @Test
    void onModifyRejectedKeepsOriginalPriceAndSize() {
        goLive(1L, 100L, 10L);
        slot.onModifySubmitted(101L, 20L);
        slot.onModifyRejected();

        assertEquals(OrderSlot.State.LIVE, slot.getState());
        assertEquals(100L, slot.getActivePrice());
        assertEquals(10L, slot.getActiveSize());
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
        assertEquals(0L, slot.getActiveSize());
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
}
