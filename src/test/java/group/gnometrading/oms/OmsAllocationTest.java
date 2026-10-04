package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSnapshots;
import group.gnometrading.schemas.Statics;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/** The OMS loop's per-pass risk work must not allocate, killed or not; a regression here means GC on the hot path. */
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
    }

    // The first run pays for class loading and JIT compilation; the second shows what each pass costs.
    private long steadyStateAllocation(final OmsTestHarness h) {
        allocatedBy(h);
        return allocatedBy(h);
    }

    private long allocatedBy(final OmsTestHarness h) {
        final long threadId = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int pass = 0; pass < PASSES; pass++) {
            h.oms.applyRiskChanges(h.sink, () -> 1L);
        }
        return threads.getThreadAllocatedBytes(threadId) - before;
    }
}
