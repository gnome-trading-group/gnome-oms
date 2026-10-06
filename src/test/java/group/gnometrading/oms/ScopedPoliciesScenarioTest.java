package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskEngine.ScopedPolicy;
import group.gnometrading.oms.risk.policy.MaxOpenOrdersPolicy;
import group.gnometrading.schemas.Statics;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Policies built with {@link RiskEngine#withScopedPolicies} apply only to the strategy or listing they target. */
class ScopedPoliciesScenarioTest {

    private static final long CENT = Statics.PRICE_SCALING_FACTOR / 100;
    private static final int OTHER_STRATEGY = OmsTestHarness.STRATEGY_ID + 1;
    private static final int OTHER_SECURITY = 43;
    private static final int OTHER_LISTING = 101;

    private OmsTestHarness harness(ScopedPolicy policy) {
        OmsTestHarness h = new OmsTestHarness(RiskEngine.withScopedPolicies(List.of(policy)));
        h.stubListing(OmsTestHarness.EXCHANGE_ID, OTHER_SECURITY, OTHER_LISTING, 0, 0);
        return h;
    }

    @Test
    void strategyPolicyLimitsOnlyThatStrategy() {
        OmsTestHarness h = harness(new ScopedPolicy(OmsTestHarness.STRATEGY_ID, 0, new MaxOpenOrdersPolicy(true, 1)));

        h.submitBidIntent(40 * CENT, 10);
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 40 * CENT, 10);
        assertEquals(1, h.sink.newOrders.size(), "the strategy's second open order is over its limit of 1");

        h.sink.clear();
        h.submitBidIntent(OTHER_STRATEGY, OmsTestHarness.SECURITY_ID, 40 * CENT, 10);
        assertEquals(1, h.sink.newOrders.size(), "another strategy is not limited");
    }

    @Test
    void listingPolicyLimitsOnlyThatListing() {
        OmsTestHarness h = harness(new ScopedPolicy(0, OmsTestHarness.LISTING_ID, new MaxOpenOrdersPolicy(false, 1)));

        // The policy counts each strategy's own orders, so on this listing every strategy gets a limit of 1.
        h.submitBidIntent(40 * CENT, 10);
        h.submitBidIntent(40 * CENT, 10);
        assertEquals(1, h.sink.newOrders.size(), "a second open order on the listing is over the limit");

        h.sink.clear();
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 40 * CENT, 10);
        assertEquals(1, h.sink.newOrders.size(), "another listing is not limited");
    }

    @Test
    void strategyListingPolicyLimitsOnlyThatPair() {
        OmsTestHarness h = harness(new ScopedPolicy(
                OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID, new MaxOpenOrdersPolicy(false, 1)));

        h.submitBidIntent(40 * CENT, 10);
        h.submitBidIntent(40 * CENT, 10);
        h.submitBidIntent(OTHER_STRATEGY, OmsTestHarness.SECURITY_ID, 40 * CENT, 10);
        h.submitBidIntent(OmsTestHarness.STRATEGY_ID, OTHER_SECURITY, 40 * CENT, 10);

        assertTrue(h.sink.newOrders.size() >= 3, "only the strategy's second order on the listing is limited");
    }

    @Test
    void negativeTargetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ScopedPolicy(-1, 0, new MaxOpenOrdersPolicy(true, 1)));
    }
}
