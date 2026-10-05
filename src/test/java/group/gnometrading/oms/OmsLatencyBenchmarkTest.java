package group.gnometrading.oms;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.Statics;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.Test;

/**
 * Reports the OMS loop's per-pass cost and an intent's latency while a strategy holds a position under a loss
 * limit, with the production SecurityMaster cache rather than a mock. Prints numbers instead of asserting tight
 * bounds, since timings vary by machine; compare runs before and after a change.
 */
class OmsLatencyBenchmarkTest {

    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 100;
    private static final int STRATEGY_ID = 7;
    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;
    private static final long PRICE = Statics.PRICE_SCALING_FACTOR / 2;
    private static final int WARMUP = 2_000_000;
    private static final int RUNS = 2_000_000;
    private static final int ROUNDS = 5;

    private final SharedPriceBuffer prices = new SharedPriceBuffer(1);
    private final PriceSlotRegistry slots = new PriceSlotRegistry(1);
    private final EpochNanoClock clock = () -> 1L;
    private final NoOpSink sink = new NoOpSink();
    private OrderManagementSystem oms;
    private int slot;

    @Test
    void reportPerPassCostAndIntentLatencyWithAPositionUnderALossLimit() {
        slot = slots.register(LISTING_ID);
        prices.register();
        prices.writeQuote(slot, PRICE - 10_000_000L, PRICE + 10_000_000L);
        final RiskEngine engine = RiskEngine.withPolicies(
                new OrderRiskPolicy[] {},
                new MarketRiskPolicy[] {new MaxTotalPnlLossPolicy(prices, slots, Long.MAX_VALUE / 4)});
        oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(16)),
                engine,
                OmsTestHarness.cachedSecurityMaster(EXCHANGE_ID, SECURITY_ID, LISTING_ID),
                prices,
                slots,
                clock);
        holdAPositionWithARestingBid();
        final Intent unchanged = OmsTestHarness.buildIntent(
                STRATEGY_ID, EXCHANGE_ID, SECURITY_ID, PRICE, 5 * UNIT, IntentDecoder.askPriceNullValue(), 0);

        final double riskChanges = nanosPer(() -> oms.applyRiskChanges(sink));
        final double intent = nanosPer(() -> oms.processIntent(unchanged, sink));
        final double quoteWrite = nanosPer(this::tick);
        final double markCheckIdle = nanosPer(() -> oms.checkMarkMoves(sink));
        final double markCheckAfterTick = nanosPer(() -> {
                    tick();
                    oms.checkMarkMoves(sink);
                })
                - quoteWrite;
        System.out.printf(
                "Mark checks: idle %.1f ns per pass, after a tick %.1f ns%n", markCheckIdle, markCheckAfterTick);
        System.out.printf(
                "OMS latency: risk changes %.1f ns per pass, quote write %.1f ns, intent %.1f ns%n",
                riskChanges, quoteWrite, intent);
    }

    private long tickCount;

    private void tick() {
        final long move = (tickCount++ & 1) * 1_000_000L;
        prices.writeQuote(slot, PRICE - 10_000_000L + move, PRICE + 10_000_000L + move);
    }

    /** The best of several rounds, since a round that shares the core with GC or the OS only reads slower. */
    private static double nanosPer(final Runnable body) {
        for (int i = 0; i < WARMUP; i++) {
            body.run();
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            final long start = System.nanoTime();
            for (int i = 0; i < RUNS; i++) {
                body.run();
            }
            best = Math.min(best, (System.nanoTime() - start) / (double) RUNS);
        }
        return best;
    }

    private void holdAPositionWithARestingBid() {
        oms.processIntent(
                OmsTestHarness.buildIntent(
                        STRATEGY_ID, EXCHANGE_ID, SECURITY_ID, PRICE, 10 * UNIT, IntentDecoder.askPriceNullValue(), 0),
                sink);
        final long counter = sink.lastOrderCounter;
        report(counter, ExecType.NEW, 0, 0, 0, 10 * UNIT);
        report(counter, ExecType.PARTIAL_FILL, 5 * UNIT, PRICE, 5 * UNIT, 5 * UNIT);
    }

    private void report(
            final long counter,
            final ExecType type,
            final long filled,
            final long price,
            final long cum,
            final long leaves) {
        final OrderExecutionReport r = new OrderExecutionReport();
        r.encodeClientOid(counter, STRATEGY_ID);
        r.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(type)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty(filled)
                .fillPrice(price)
                .cumulativeQty(cum)
                .leavesQty(leaves)
                .fee(0);
        oms.processExecutionReport(r, sink);
    }

    private static final class NoOpSink implements ActionSink {
        long lastOrderCounter;

        @Override
        public void onNewOrder(final Order order) {
            lastOrderCounter = order.getClientOidCounter();
        }

        @Override
        public void onCancel(final CancelOrder cancel) {}

        @Override
        public void onModify(final ModifyOrder modify) {}

        @Override
        public void onExecReport(final OrderExecutionReport report) {}
    }
}
