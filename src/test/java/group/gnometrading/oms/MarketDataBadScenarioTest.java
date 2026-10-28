package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A listing whose book is flagged bad data, through the real OMS: new risk is refused, resting orders stay. */
class MarketDataBadScenarioTest {

    private static final long PX = Statics.PRICE_SCALING_FACTOR / 100;

    private final SharedPriceBuffer prices = new SharedPriceBuffer(4);
    private final PriceSlotRegistry slots = new PriceSlotRegistry(4);
    private OmsTestHarness h;
    private int slot;

    @BeforeEach
    void setUp() {
        h = new OmsTestHarness(new RiskEngine(), prices, slots);
        h.quote(OmsTestHarness.LISTING_ID, OmsTestHarness.TOUCH_BID, OmsTestHarness.TOUCH_ASK);
        slot = slots.getSlot(OmsTestHarness.LISTING_ID);
    }

    @Test
    void newQuotesAndModifiesAreRefusedWhileRestingOrdersStay() {
        long bid = h.submitBidIntent(30 * PX, 10);
        h.injectAck(bid);
        h.sink.clear();

        prices.markBad(slot, 5_000);
        // Intents state the whole quote: keep the bid and add an ask, then move the bid.
        h.submitBothIntent(30 * PX, 10, 70 * PX, 10);
        h.submitBothIntent(31 * PX, 10, 70 * PX, 10);

        assertEquals(0, h.sink.newOrders.size(), "nothing new reaches the venue");
        assertEquals(0, h.sink.modifies.size(), "the resting bid is not moved");
        assertEquals(0, h.sink.cancels.size(), "nor cancelled");
        assertTrue(h.sink.execReports.stream()
                .anyMatch(r -> r.execType() == ExecType.REJECT && r.rejectReason() == RejectReason.MARKET_DATA_BAD));
        assertTrue(h.sink.execReports.stream()
                .noneMatch(r -> r.rejectReason() != RejectReason.MARKET_DATA_BAD && r.execType() == ExecType.REJECT));
    }

    @Test
    void takesAreRefused() {
        prices.markBad(slot, 5_000);
        h.submitTakeIntent(5, Side.Bid);

        assertEquals(0, h.sink.newOrders.size());
        assertEquals(RejectReason.MARKET_DATA_BAD, h.sink.execReports.get(0).rejectReason());
    }

    @Test
    void cancelsStillGoOut() {
        long bid = h.submitBidIntent(30 * PX, 10);
        h.injectAck(bid);
        h.sink.clear();

        prices.markBad(slot, 5_000);
        h.submitCancelIntent();

        assertEquals(List.of(bid), h.sink.cancels);
    }

    @Test
    void aGoodBookLetsOrdersThroughAgain() {
        prices.markBad(slot, 5_000);
        h.submitBidIntent(30 * PX, 10);
        assertEquals(0, h.sink.newOrders.size());

        prices.markGood(slot);
        h.submitBidIntent(30 * PX, 10);

        assertEquals(1, h.sink.newOrders.size());
    }

    @Test
    void aListingWithNoDataYetIsNotBlocked() {
        h.stubListing(OmsTestHarness.EXCHANGE_ID, 43, 101, 0, 0);
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, 43, 30 * PX, 10);

        assertEquals(1, h.sink.newOrders.size());
    }
}
