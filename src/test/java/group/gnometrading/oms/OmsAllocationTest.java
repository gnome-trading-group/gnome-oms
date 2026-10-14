package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RejectStreaks;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskEngine.ScopedPolicy;
import group.gnometrading.oms.risk.RiskSnapshots;
import group.gnometrading.oms.risk.TestListings;
import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.schemas.Statics;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The OMS loop's per-pass risk work must not allocate, killed or not; a regression here means GC on the hot path. */
@Tag("allocation")
class OmsAllocationTest {

    private static final int PASSES = 1_000_000;
    // Room for the measurement itself; a single allocation per pass would be tens of megabytes.
    private static final long ALLOWED_BYTES = 16 * 1024;

    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void applyingRiskChangesAllocatesNothingWhetherOrNotAScopeIsKilled() {
        final RiskEngine engine = new RiskEngine();
        final OmsTestHarness h = new OmsTestHarness(engine);
        h.submitBidIntent(100L * Statics.SIZE_SCALING_FACTOR, 10L);
        h.applyRiskChanges();
        assertTrue(steadyStateAllocation(h) < ALLOWED_BYTES, "idle");

        RiskSnapshots.publishKills(engine, false, new int[] {OmsTestHarness.STRATEGY_ID + 1}, new int[] {});
        h.applyRiskChanges();
        assertTrue(steadyStateAllocation(h) < ALLOWED_BYTES, "with a kill active");

        RiskSnapshots.publishExchangeKills(engine, OmsTestHarness.EXCHANGE_ID + 1);
        engine.latch(OmsTestHarness.STRATEGY_ID + 1, OmsTestHarness.EXCHANGE_ID, 0, RiskEngine.CAUSE_REJECT_STREAK, 0);
        engine.latch(OmsTestHarness.STRATEGY_ID + 1, 0, OmsTestHarness.LISTING_ID, RiskEngine.CAUSE_MARKET_LIMIT, 0);
        h.applyRiskChanges();
        assertTrue(steadyStateAllocation(h) < ALLOWED_BYTES, "with exchange kills and scoped latches active");
    }

    @Test
    void checkingLossLimitsOnEveryMarkMoveAllocatesNothing() {
        final long px = Statics.SIZE_SCALING_FACTOR;
        final int otherSecurity = 43;
        final int otherListing = 101;
        final SharedPriceBuffer prices = new SharedPriceBuffer(2);
        final PriceSlotRegistry slots = new PriceSlotRegistry(2);
        final int slot = slots.register(OmsTestHarness.LISTING_ID);
        final int otherSlot = slots.register(otherListing);
        final ListingDirectory directory = TestListings.directory(
                TestListings.binary(OmsTestHarness.LISTING_ID, OmsTestHarness.EXCHANGE_ID),
                TestListings.binary(otherListing, OmsTestHarness.EXCHANGE_ID));
        final RejectStreaks streaks = new RejectStreaks();
        final long unlimited = Long.MAX_VALUE / 4;
        final RiskEngine engine = RiskEngine.withScopedPolicies(
                List.of(
                        new ScopedPolicy(0, 0, 0, new MaxTotalPnlLossPolicy(prices, slots, directory, 0, 0, unlimited)),
                        new ScopedPolicy(
                                OmsTestHarness.STRATEGY_ID,
                                0,
                                OmsTestHarness.EXCHANGE_ID,
                                new MaxTotalPnlLossPolicy(
                                        prices, slots, directory, 0, OmsTestHarness.EXCHANGE_ID, unlimited)),
                        new ScopedPolicy(0, 0, 0, new MaxConsecutiveRejectsPolicy(streaks, 0, 0, 1_000)),
                        new ScopedPolicy(
                                0,
                                0,
                                OmsTestHarness.EXCHANGE_ID,
                                new MaxConsecutiveRejectsPolicy(streaks, 0, OmsTestHarness.EXCHANGE_ID, 1_000))),
                directory,
                streaks);
        final OmsTestHarness h = new OmsTestHarness(engine, prices, slots);
        h.stubListing(OmsTestHarness.EXCHANGE_ID, otherSecurity, otherListing, 0, 0);
        final long buy = h.submitBidIntent(100L * px, 10L);
        h.injectAck(buy, 10);
        h.injectFill(buy, 10, 100 * px, 10, 0);
        final long sell = h.submitAskIntent(OmsTestHarness.STRATEGY_ID, otherSecurity, 100L * px, 10L);
        h.injectAck(OmsTestHarness.STRATEGY_ID, sell, OmsTestHarness.EXCHANGE_ID, otherSecurity, 10);
        h.injectFill(
                OmsTestHarness.STRATEGY_ID, sell, OmsTestHarness.EXCHANGE_ID, otherSecurity, 10, 100 * px, 10, 0, 0);

        final long[] tick = {0};
        final Runnable pass = () -> {
            final long move = (tick[0]++ & 1) * px;
            prices.writeQuote(slot, 99 * px + move, 101 * px + move);
            prices.writeQuote(otherSlot, 99 * px - move, 101 * px - move);
            h.oms.applyRiskChanges(h.sink);
            h.oms.checkMarkMoves(h.sink);
        };
        allocatedBy(pass);
        assertTrue(allocatedBy(pass) < ALLOWED_BYTES, "a mark move on every pass");
    }

    // The first run pays for class loading and JIT compilation; the second shows what each pass costs.
    private long steadyStateAllocation(final OmsTestHarness h) {
        final Runnable pass = () -> h.oms.applyRiskChanges(h.sink);
        allocatedBy(pass);
        return allocatedBy(pass);
    }

    private long allocatedBy(final Runnable pass) {
        final long threadId = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < PASSES; i++) {
            pass.run();
        }
        return threads.getThreadAllocatedBytes(threadId) - before;
    }
}
