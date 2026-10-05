package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.policy.MaxOpenOrdersPolicy;
import group.gnometrading.oms.risk.policy.PriceCollarPolicy;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Statics;
import org.junit.jupiter.api.Test;

/** Max open orders and the price collar, through the real OMS: what reaches the venue and what is rejected. */
class OrderLimitsScenarioTest {

    private static final long CENT = Statics.PRICE_SCALING_FACTOR / 100;
    private static final int OTHER_SECURITY = 43;
    private static final int OTHER_LISTING = 101;

    private OmsTestHarness h;

    @Test
    void ordersUpToTheLimitPassAndTheNextIsRejectedUntilOneIsReleased() {
        withPolicies(new MaxOpenOrdersPolicy(true, 2));
        long bid = h.submitBidIntent(40 * CENT, 10);
        h.injectAck(bid, 10);
        long ask = h.submitAskIntent(60 * CENT, 10);
        h.injectAck(ask, 10);
        h.sink.clear();

        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 40 * CENT, 10);
        assertTrue(h.sink.newOrders.isEmpty(), "a third open order is over the strategy's limit of 2");
        assertTrue(rejected(), "the strategy hears about the rejection");

        h.submitBidIntent(0, 0); // pull the bid
        h.injectCancel(bid);
        h.sink.clear();
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 40 * CENT, 10);
        assertEquals(1, h.sink.newOrders.size(), "the cancelled bid freed its place");
    }

    @Test
    void aCancelThenReplaceRepriceAtTheLimitIsNotBlockedByTheOrderItReplaces() {
        withPolicies(new MaxOpenOrdersPolicy(false, 1));
        h.stubListing(
                OmsTestHarness.EXCHANGE_ID,
                OmsTestHarness.SECURITY_ID,
                OmsTestHarness.LISTING_ID,
                0,
                0,
                OmsTestHarness.CANCEL_REPLACE_EXCHANGE_CODE);
        long bid = h.submitBidIntent(40 * CENT, 10);
        h.injectAck(bid, 10);
        h.sink.clear();

        h.submitBidIntent(41 * CENT, 10); // no native modify: cancel, then send the replacement
        assertEquals(java.util.List.of(bid), h.sink.cancels);
        h.injectCancel(bid);

        assertEquals(1, h.sink.newOrders.size(), "the replacement goes out once the original is cancelled");
        assertEquals(41 * CENT, h.sink.newOrders.get(0).price());
    }

    @Test
    void theCollarRejectsAFatFingerOrderAndAFatFingerModify() {
        final SharedPriceBuffer prices = new SharedPriceBuffer(1);
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        prices.writeQuote(slots.register(OmsTestHarness.LISTING_ID), 39 * CENT, 41 * CENT); // mark 40¢
        h = new OmsTestHarness(
                RiskEngine.withPolicies(
                        new OrderRiskPolicy[] {new PriceCollarPolicy(prices, slots, 10 * CENT)},
                        new MarketRiskPolicy[] {}),
                prices,
                slots);

        h.submitBidIntent(95 * CENT, 10);
        assertTrue(h.sink.newOrders.isEmpty(), "a bid 55¢ through the market");

        long bid = h.submitBidIntent(45 * CENT, 10);
        h.injectAck(bid, 10);
        h.sink.clear();
        h.submitBidIntent(95 * CENT, 10);
        assertTrue(h.sink.modifies.isEmpty(), "repricing the resting bid through the market");
    }

    private void withPolicies(final OrderRiskPolicy... policies) {
        h = new OmsTestHarness(RiskEngine.withPolicies(policies, new MarketRiskPolicy[] {}));
        h.stubListing(OmsTestHarness.EXCHANGE_ID, OTHER_SECURITY, OTHER_LISTING, 0, 0);
    }

    private boolean rejected() {
        return h.sink.execReports.stream().anyMatch(r -> r.execType() == ExecType.REJECT);
    }
}
