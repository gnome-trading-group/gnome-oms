package group.gnometrading.oms.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedPriceBufferTest {

    private SharedPriceBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new SharedPriceBuffer(8);
    }

    @Test
    void register_returnsSequentialSlots() {
        assertEquals(0, buffer.register());
        assertEquals(1, buffer.register());
        assertEquals(2, buffer.register());
    }

    @Test
    void priceEpoch_advancesOnEveryPriceChangeButNotARepeat() {
        int slot = buffer.register();
        int other = buffer.register();
        long start = buffer.priceEpoch();

        buffer.writeQuote(slot, 10L, 12L);
        assertEquals(start + 1, buffer.priceEpoch());
        buffer.writeTrade(other, 11L);
        assertEquals(start + 2, buffer.priceEpoch());

        buffer.writeQuote(slot, 10L, 12L);
        buffer.writeTrade(other, 11L);
        assertEquals(start + 2, buffer.priceEpoch(), "an unchanged top of book or trade price is not a change");
    }

    @Test
    void slotVersion_changesOnlyForTheSlotWritten() {
        int slot = buffer.register();
        int other = buffer.register();
        long slotBefore = buffer.slotVersion(slot);
        long otherBefore = buffer.slotVersion(other);

        buffer.writeQuote(slot, 10L, 12L);

        assertNotEquals(slotBefore, buffer.slotVersion(slot));
        assertEquals(otherBefore, buffer.slotVersion(other));
        assertEquals(11L, buffer.markPrice(slot));
    }

    @Test
    void capacity_returnsMaxSlots() {
        assertEquals(8, buffer.capacity());
    }

    @Test
    void freshSlot_hasNoPrice() {
        int slot = buffer.register();
        assertEquals(0L, buffer.executionPrice(slot, Side.Bid));
        assertEquals(0L, buffer.executionPrice(slot, Side.Ask));
        assertEquals(0L, buffer.markPrice(slot));
    }

    @Test
    void executionPrice_buyUsesAsk_sellUsesBid() {
        int slot = buffer.register();
        buffer.writeQuote(slot, 40L, 44L);
        buffer.writeTrade(slot, 10L);

        assertEquals(44L, buffer.executionPrice(slot, Side.Bid));
        assertEquals(40L, buffer.executionPrice(slot, Side.Ask));
    }

    @Test
    void executionPrice_fallsBackToLastTradeWhenSideEmpty() {
        int slot = buffer.register();
        buffer.writeQuote(slot, 40L, 0L);
        buffer.writeTrade(slot, 42L);

        assertEquals(42L, buffer.executionPrice(slot, Side.Bid));
        assertEquals(40L, buffer.executionPrice(slot, Side.Ask));
    }

    @Test
    void markPrice_isMidWhenBothSidesQuoted() {
        int slot = buffer.register();
        buffer.writeQuote(slot, 40L, 45L);
        buffer.writeTrade(slot, 10L);

        assertEquals(42L, buffer.markPrice(slot));
    }

    @Test
    void markPrice_fallsBackToLastTradeWhenEitherSideEmpty() {
        int slot = buffer.register();
        buffer.writeTrade(slot, 30L);
        buffer.writeQuote(slot, 0L, 45L);

        assertEquals(30L, buffer.markPrice(slot));
    }

    @Test
    void writeQuote_replacesPreviousQuote() {
        int slot = buffer.register();
        buffer.writeQuote(slot, 40L, 44L);
        buffer.writeQuote(slot, 41L, 43L);

        assertEquals(43L, buffer.executionPrice(slot, Side.Bid));
        assertEquals(41L, buffer.executionPrice(slot, Side.Ask));
    }

    @Test
    void multipleSlots_areIndependent() {
        int slot0 = buffer.register();
        int slot1 = buffer.register();

        buffer.writeQuote(slot0, 1000L, 1002L);
        buffer.writeQuote(slot1, 2000L, 2002L);

        assertEquals(1001L, buffer.markPrice(slot0));
        assertEquals(2001L, buffer.markPrice(slot1));
    }
}
