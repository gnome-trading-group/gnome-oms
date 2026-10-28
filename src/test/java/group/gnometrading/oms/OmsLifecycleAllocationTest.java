package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerEvent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.Configurable;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RejectStreaks;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.TestListings;
import group.gnometrading.oms.risk.policy.MaxCommittedCapitalPolicy;
import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.risk.policy.MaxOpenOrdersPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderNotionalPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxPositionPolicy;
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
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import java.lang.management.ManagementFactory;
import java.util.List;
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
        final ListingDirectory directory =
                TestListings.directory(TestListings.binary(OmsTestHarness.LISTING_ID, OmsTestHarness.EXCHANGE_ID));
        final RejectStreaks streaks = new RejectStreaks();
        final long unlimited = Long.MAX_VALUE / 4;
        final RiskEngine engine = RiskEngine.withScopedPolicies(
                List.of(
                        everywhere(new MaxOrderSizePolicy(unlimited)),
                        everywhere(new MaxOrderNotionalPolicy(unlimited)),
                        everywhere(new PriceCollarPolicy(prices, slots, unlimited)),
                        everywhere(new MaxOpenOrdersPolicy(directory, 0, 0, 1_000)),
                        everywhere(new MaxPositionPolicy(unlimited)),
                        everywhere(new MaxCommittedCapitalPolicy(directory, 0, 0, unlimited)),
                        onExchange(new MaxCommittedCapitalPolicy(directory, 0, OmsTestHarness.EXCHANGE_ID, unlimited)),
                        everywhere(new MaxTotalPnlLossPolicy(prices, slots, directory, 0, 0, unlimited)),
                        onExchange(new MaxTotalPnlLossPolicy(
                                prices, slots, directory, 0, OmsTestHarness.EXCHANGE_ID, unlimited)),
                        everywhere(new MaxConsecutiveRejectsPolicy(streaks, 0, 0, 1_000)),
                        onExchange(new MaxConsecutiveRejectsPolicy(streaks, 0, OmsTestHarness.EXCHANGE_ID, 1_000))),
                directory,
                streaks);
        final DefaultPositionTracker positions = new DefaultPositionTracker(new SharedPositionBuffer(16));
        final LedgerRing ledger = new LedgerRing(1024, LedgerAgent.MAX_EVENTS_PER_BATCH, Long.MAX_VALUE / 4, positions);
        final int[] drained = {0};
        final MessageConsumer<LedgerEvent> drain = event -> drained[0]++;
        final OrderManagementSystem oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(64),
                positions,
                engine,
                OmsTestHarness.cachedSecurityMaster(
                        OmsTestHarness.EXCHANGE_ID, OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID),
                prices,
                slots,
                ledger,
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

        // Quote, reprice, partial fill, pull, then flatten, with a mark move, the loss check and every order and fill
        // recorded in the ledger on every cycle.
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
            oms.processIntent(bid, sink);
            reports.reject(sink.lastOrderCounter, RejectReason.EXCHANGE_REJECTED);
            // A new bad-data spell each cycle: the bid is refused and the refusal logged, then a good book clears it.
            prices.markBad(slot, tick[0]);
            oms.processIntent(bid, sink);
            prices.markGood(slot);
            oms.checkMarkMoves(sink);
            oms.checkLedger(sink);
            // Stands in for the ledger agent, so the ring never fills.
            drained[0] = 0;
            ledger.read(drain, 1024);
            ledger.acknowledge(drained[0]);
        };
        allocatedBy(lifecycle, LIFECYCLES);
        final long allocated = allocatedBy(lifecycle, LIFECYCLES);
        assertTrue(allocated < ALLOWED_BYTES, "intents, exec reports and risk per cycle: " + allocated + " bytes");
        // Two orders opened, acknowledged, filled and closed.
        assertTrue(drained[0] >= 8, "each cycle recorded its orders and fills: " + drained[0]);
    }

    private static RiskEngine.ScopedPolicy everywhere(final Configurable policy) {
        return new RiskEngine.ScopedPolicy(0, 0, 0, policy);
    }

    private static RiskEngine.ScopedPolicy onExchange(final Configurable policy) {
        return new RiskEngine.ScopedPolicy(0, 0, OmsTestHarness.EXCHANGE_ID, policy);
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

        void reject(final long counter, final RejectReason reason) {
            encode(counter, ExecType.REJECT, 0, 0, 0, 0);
            report.encoder.rejectReason(reason);
            oms.processExecutionReport(report, sink);
        }

        void send(
                final long counter,
                final ExecType type,
                final long filled,
                final long price,
                final long cumulative,
                final long leaves) {
            encode(counter, type, filled, price, cumulative, leaves);
            report.encoder.rejectReason(RejectReason.NULL_VAL);
            oms.processExecutionReport(report, sink);
        }

        private void encode(
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
