package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerEvent;
import group.gnometrading.oms.ledger.LedgerEventType;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.OrderRiskPolicy;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskEngineResume;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The ledger sees every order the OMS sends and every fill it books, and trading stops when the ledger can't. */
class OmsLedgerTest {

    private static final long UNIT = 1_000_000L;
    private static final long PRICE = 500_000_000L;
    private static final long MAX_LAG_NS = 10_000_000_000L;

    private final long[] now = {1_000L};
    private DefaultPositionTracker positions;
    private LedgerRing ledger;
    private RiskEngine riskEngine;
    private OrderManagementSystem oms;
    private OmsTestHarness.RecordingSink sink;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        ledger = new LedgerRing(64, LedgerAgent.MAX_EVENTS_PER_BATCH, MAX_LAG_NS, positions);
        riskEngine = RiskEngine.withOrderPolicies(new OrderRiskPolicy[0]);
        oms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(16),
                positions,
                riskEngine,
                OmsTestHarness.cachedSecurityMaster(
                        OmsTestHarness.EXCHANGE_ID, OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID),
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                ledger,
                () -> now[0]);
        sink = new OmsTestHarness.RecordingSink();
    }

    @Test
    void anOrdersWholeLifeIsRecordedInOrder() {
        final long counter = placeBid(10 * UNIT);
        report(counter, ExecType.NEW, "4071-100-1", 0, 0, 0);
        report(counter, ExecType.PARTIAL_FILL, "4071-100-1", 4 * UNIT, 4 * UNIT, 6 * UNIT);
        report(counter, ExecType.FILL, "4071-100-1", 6 * UNIT, 10 * UNIT, 0);

        final List<LedgerEvent> events = drain();
        assertEquals(
                List.of(
                        LedgerEventType.ORDER_OPENED,
                        LedgerEventType.ORDER_ACKED,
                        LedgerEventType.FILL,
                        LedgerEventType.FILL,
                        LedgerEventType.ORDER_CLOSED),
                events.stream().map(event -> event.type).toList());

        final LedgerEvent opened = events.get(0);
        assertEquals(OmsTestHarness.LISTING_ID, opened.listingId);
        assertEquals(OmsTestHarness.EXCHANGE_ID, opened.exchangeId);
        assertEquals(Side.Bid, opened.side);
        assertEquals(PRICE, opened.price);
        assertEquals(10 * UNIT, opened.size);

        assertEquals("4071-100-1", new String(events.get(1).exchangeOrderId, 0, events.get(1).exchangeOrderIdLength));

        final LedgerEvent firstFill = events.get(2);
        assertEquals(4 * UNIT, firstFill.cumQtyAfter);
        assertEquals(4 * UNIT, firstFill.netQuantityAfter);
        assertEquals(1, firstFill.positionVersion);
        final LedgerEvent lastFill = events.get(3);
        assertEquals(10 * UNIT, lastFill.cumQtyAfter);
        assertEquals(10 * UNIT, lastFill.netQuantityAfter);
        assertEquals(5 * UNIT * 1_000, lastFill.totalCostAfter, "10 at 50c is $5");
        assertEquals(2, lastFill.positionVersion);

        assertEquals(10 * UNIT, events.get(4).cumQtyAfter, "closed fully filled");
    }

    @Test
    void anInheritedPositionKeepsCountingItsVersion() {
        positions.seedStrategyPosition(
                OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID, 2 * UNIT, UNIT * 1_000, 9);
        final long counter = placeBid(UNIT);
        report(counter, ExecType.FILL, "4071-100-1", UNIT, UNIT, 0);

        final LedgerEvent fill = drain().stream()
                .filter(event -> event.type == LedgerEventType.FILL)
                .findFirst()
                .orElseThrow();
        assertEquals(10, fill.positionVersion);
        assertEquals(3 * UNIT, fill.netQuantityAfter);
    }

    @Test
    void anOrderTheGatewayRefusedClosesWithoutAnAck() {
        final long counter = placeBid(UNIT);
        report(counter, ExecType.REJECT, "", 0, 0, 0);

        assertEquals(
                List.of(LedgerEventType.ORDER_OPENED, LedgerEventType.ORDER_CLOSED),
                drain().stream().map(event -> event.type).toList());
    }

    @Test
    void aLedgerFallingBehindHaltsTheStrategyAndCancelsItsWorkingOrders() {
        final long counter = placeBid(UNIT);
        report(counter, ExecType.NEW, "4071-100-1", 0, 0, 0);
        drain(); // read but never confirmed, as when the registry is down

        now[0] += MAX_LAG_NS + 1;
        runLedgerChecks();

        assertTrue(riskEngine.isStrategyHalted(OmsTestHarness.STRATEGY_ID));
        assertEquals(List.of(counter), sink.cancels);

        sink.clear();
        oms.processIntent(bidIntent(UNIT), sink);
        assertTrue(sink.newOrders.isEmpty(), "nothing new goes out");
    }

    @Test
    void aResumedStrategyIsHaltedAgainWhileTheLedgerIsStillFailing() {
        placeBid(UNIT);
        drain();
        now[0] += MAX_LAG_NS + 1;
        runLedgerChecks();
        RiskEngineResume.resume(riskEngine, OmsTestHarness.STRATEGY_ID);
        assertFalse(riskEngine.isStrategyHalted(OmsTestHarness.STRATEGY_ID), "the operator resumed it");

        runLedgerChecks();
        assertTrue(riskEngine.isStrategyHalted(OmsTestHarness.STRATEGY_ID));
    }

    @Test
    void aHealthyLedgerLeavesTradingAlone() {
        placeBid(UNIT);
        ledger.acknowledge(drain().size());
        now[0] += MAX_LAG_NS + 1;
        runLedgerChecks();
        assertFalse(riskEngine.isStrategyHalted(OmsTestHarness.STRATEGY_ID));
    }

    @Test
    void anOrderRefusedWhileRiskIsStaleIsRejectedAsHalted() {
        final OrderManagementSystem staleOms = new OrderManagementSystem(
                new NullLogger(),
                new PooledOrderStateManager(16),
                positions,
                RiskEngine.syncedFromRegistry(() -> 0L, java.time.Duration.ofSeconds(30)),
                OmsTestHarness.cachedSecurityMaster(
                        OmsTestHarness.EXCHANGE_ID, OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID),
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                ledger,
                () -> now[0]);

        staleOms.processIntent(bidIntent(UNIT), sink);

        assertTrue(sink.newOrders.isEmpty());
        assertEquals(RejectReason.HALTED, sink.execReports.get(0).rejectReason());
        assertTrue(drain().isEmpty(), "an order never sent is not recorded");
    }

    private void runLedgerChecks() {
        for (int i = 0; i < OrderManagementSystem.LEDGER_CHECK_PASSES; i++) {
            oms.checkLedger(sink);
        }
    }

    private long placeBid(final long size) {
        oms.processIntent(bidIntent(size), sink);
        return sink.lastNewOrderCounter();
    }

    private static group.gnometrading.schemas.Intent bidIntent(final long size) {
        return OmsTestHarness.buildIntent(
                OmsTestHarness.STRATEGY_ID,
                OmsTestHarness.EXCHANGE_ID,
                OmsTestHarness.SECURITY_ID,
                PRICE,
                size,
                IntentDecoder.askPriceNullValue(),
                0);
    }

    private void report(
            final long counter,
            final ExecType type,
            final String exchangeOrderId,
            final long filled,
            final long cumulative,
            final long leaves) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(counter, OmsTestHarness.STRATEGY_ID);
        report.encoder
                .exchangeId(OmsTestHarness.EXCHANGE_ID)
                .securityId(OmsTestHarness.SECURITY_ID)
                .exchangeOrderId(exchangeOrderId)
                .execType(type)
                .orderStatus(OrderStatus.NULL_VAL)
                .filledQty(filled)
                .fillPrice(PRICE)
                .cumulativeQty(cumulative)
                .leavesQty(leaves)
                .fee(0)
                .timestampRecv(now[0]);
        oms.processExecutionReport(report, sink);
    }

    private List<LedgerEvent> drain() {
        final List<LedgerEvent> events = new ArrayList<>();
        ledger.read(
                event -> {
                    final LedgerEvent copy = new LedgerEvent();
                    copy.copyFrom(event);
                    events.add(copy);
                },
                64);
        return events;
    }
}
