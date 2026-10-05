package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.policy.MaxNotionalValuePolicy;
import group.gnometrading.oms.risk.policy.MaxOpenOrdersPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.risk.policy.PriceCollarPolicy;
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
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An order's whole life through the OMS — intents, exec reports, risk checks and a mark move — must not allocate
 * once warm. It runs against the production SecurityMaster in a JVM where nothing has mocked it.
 */
@Tag("allocation")
class OmsLifecycleAllocationTest {

    private static final int LIFECYCLES = 200_000;
    // Room for the measurement itself; a single allocation per cycle would be megabytes.
    private static final long ALLOWED_BYTES = 16 * 1024;

    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void aFullOrderLifecycleAllocatesNothing() {
        final long unit = Statics.SIZE_SCALING_FACTOR;
        final long price = Statics.PRICE_SCALING_FACTOR / 2;
        final SharedPriceBuffer prices = new SharedPriceBuffer(1);
        final PriceSlotRegistry slots = new PriceSlotRegistry(1);
        final int slot = slots.register(OmsTestHarness.LISTING_ID);
        final RiskEngine engine = RiskEngine.withPolicies(
                new OrderRiskPolicy[] {
                    new MaxOrderSizePolicy(Long.MAX_VALUE / 4),
                    new MaxNotionalValuePolicy(Long.MAX_VALUE / 4),
                    new PriceCollarPolicy(prices, slots, Long.MAX_VALUE / 4),
                    new MaxOpenOrdersPolicy(true, 1_000)
                },
                new MarketRiskPolicy[] {new MaxTotalPnlLossPolicy(prices, slots, true, Long.MAX_VALUE / 4)});
        final OrderManagementSystem oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(64),
                new DefaultPositionTracker(new SharedPositionBuffer(16)),
                engine,
                OmsTestHarness.cachedSecurityMaster(
                        OmsTestHarness.EXCHANGE_ID, OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID),
                prices,
                slots,
                () -> 1L);
        final LastOrderSink sink = new LastOrderSink();
        final Reports reports = new Reports(oms, sink);
        final Intent bid = bidIntent(price, 10 * unit);
        final Intent improvedBid = bidIntent(price + Statics.PRICE_SCALING_FACTOR / 100, 10 * unit);
        final Intent pull = bidIntent(IntentDecoder.bidPriceNullValue(), 0);
        final Intent ask = OmsTestHarness.buildIntent(
                OmsTestHarness.STRATEGY_ID,
                OmsTestHarness.EXCHANGE_ID,
                OmsTestHarness.SECURITY_ID,
                IntentDecoder.bidPriceNullValue(),
                0,
                price,
                4 * unit);
        final long[] tick = {0};

        // Quote, reprice, partial fill, pull, then flatten, with a mark move and the loss check on every cycle.
        final Runnable lifecycle = () -> {
            final long move = (tick[0]++ & 1) * unit;
            prices.writeQuote(slot, price - unit + move, price + unit + move);
            oms.applyRiskChanges(sink);
            oms.processIntent(bid, sink);
            final long buy = sink.lastOrderCounter;
            reports.send(buy, ExecType.NEW, 0, 0, 0, 10 * unit);
            oms.processIntent(improvedBid, sink);
            reports.send(buy, ExecType.PARTIAL_FILL, 4 * unit, price, 4 * unit, 6 * unit);
            oms.processIntent(pull, sink);
            reports.send(buy, ExecType.CANCEL, 0, 0, 4 * unit, 0);
            if (sink.lastOrderCounter != buy) {
                reports.send(sink.lastOrderCounter, ExecType.CANCEL, 0, 0, 0, 0); // a cancel/replace's replacement
            }
            oms.processIntent(ask, sink);
            final long sell = sink.lastOrderCounter;
            reports.send(sell, ExecType.NEW, 0, 0, 0, 4 * unit);
            reports.send(sell, ExecType.FILL, 4 * unit, price, 4 * unit, 0);
            oms.processIntent(pull, sink);
            oms.checkMarkMoves(sink);
        };
        allocatedBy(lifecycle, LIFECYCLES);
        final long allocated = allocatedBy(lifecycle, LIFECYCLES);
        assertTrue(allocated < ALLOWED_BYTES, "intents, exec reports and risk per cycle: " + allocated + " bytes");
    }

    private static Intent bidIntent(final long price, final long size) {
        return OmsTestHarness.buildIntent(
                OmsTestHarness.STRATEGY_ID,
                OmsTestHarness.EXCHANGE_ID,
                OmsTestHarness.SECURITY_ID,
                price,
                size,
                IntentDecoder.askPriceNullValue(),
                0);
    }

    private static final class LastOrderSink implements ActionSink {
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

    /** Sends exec reports through one reused flyweight, as the OMS agent does. */
    private static final class Reports {
        private final OrderExecutionReport report = new OrderExecutionReport();
        private final OrderManagementSystem oms;
        private final ActionSink sink;

        Reports(final OrderManagementSystem oms, final ActionSink sink) {
            this.oms = oms;
            this.sink = sink;
        }

        void send(
                final long counter,
                final ExecType type,
                final long filled,
                final long price,
                final long cumulative,
                final long leaves) {
            report.encodeClientOid(counter, OmsTestHarness.STRATEGY_ID);
            report.encoder
                    .exchangeId(OmsTestHarness.EXCHANGE_ID)
                    .securityId(OmsTestHarness.SECURITY_ID)
                    .execType(type)
                    .orderStatus(OrderStatus.NULL_VAL)
                    .filledQty(filled)
                    .fillPrice(price)
                    .cumulativeQty(cumulative)
                    .leavesQty(leaves)
                    .fee(0);
            oms.processExecutionReport(report, sink);
        }
    }

    private long allocatedBy(final Runnable cycle, final int times) {
        final long threadId = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < times; i++) {
            cycle.run();
        }
        return threads.getThreadAllocatedBytes(threadId) - before;
    }
}
