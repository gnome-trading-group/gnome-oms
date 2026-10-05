package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskEngineTest {

    @Mock
    private OrderStateManager orders;

    private DefaultPositionTracker positions;
    private Order order;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        order = new Order();
        order.encoder.side(Side.Bid).size(1).price(100);
    }

    // --- kill scopes ---

    @Test
    void testRegistryBackedEngineBlocksNewOrdersUntilTheFirstRefresh() {
        final long[] now = {1_000};
        final RiskEngine engine = RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), 1);
        assertFalse(engine.check(order, positions, orders, 1, 1));
        assertTrue(engine.isBlocked(1, 1));

        engine.recordRefresh(now[0]);
        engine.applyChanges(new RecordingKillHandler());
        assertTrue(engine.check(order, positions, orders, 1, 1));
    }

    @Test
    void testStaleRiskBlocksNewOrdersWithoutKillingAnything() {
        final long[] now = {1_000};
        final RiskEngine engine = RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), 1);
        final RecordingKillHandler handler = new RecordingKillHandler();
        engine.recordRefresh(now[0]);
        engine.applyChanges(handler);

        now[0] += 31_000;
        engine.applyChanges(handler);
        assertTrue(engine.isBlocked(1, 1));
        assertFalse(engine.hasKills(), "a stale view is not a kill: resting orders stay");

        engine.recordRefresh(now[0]);
        engine.applyChanges(handler);
        assertFalse(engine.isBlocked(1, 1));
        assertEquals("", handler.events.toString());
    }

    @Test
    void testKillScopesBlockOnlyWhatTheyCover() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.killedStrategies.add(7);
        snapshot.killedListings.add(200);
        engine.publishSnapshot(snapshot);

        assertFalse(engine.check(order, positions, orders, 7, 1));
        assertFalse(engine.check(order, positions, orders, 1, 200));
        assertTrue(engine.check(order, positions, orders, 8, 201));
    }

    @Test
    void testGlobalKillBlocksEverything() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.globalKill = true;
        engine.publishSnapshot(killed);
        assertFalse(engine.check(order, positions, orders, 8, 201));
    }

    @Test
    void testEveryKilledOrLatchedScopeIsReportedForAResweep() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategies.add(7);
        killed.killedListings.add(200);
        engine.publishSnapshot(killed);
        engine.applyChanges(new RecordingKillHandler());
        engine.latch(9);

        final RecordingKillHandler handler = new RecordingKillHandler();
        engine.forEachKilledScope(handler);

        assertEquals("strategy 7;strategy 9;listing 200;", handler.events.toString());
    }

    // --- applyChanges ---

    @Test
    void testApplyChangesReportsEachNewlyKilledScopeOnce() {
        final RiskEngine engine = new RiskEngine();
        final RecordingKillHandler handler = new RecordingKillHandler();
        final RiskEngineSnapshot first = new RiskEngineSnapshot();
        first.killedStrategies.add(7);
        engine.publishSnapshot(first);

        engine.applyChanges(handler);
        engine.applyChanges(handler);
        assertEquals("strategy 7;", handler.events.toString());

        final RiskEngineSnapshot second = new RiskEngineSnapshot();
        second.killedStrategies.add(7);
        second.killedListings.add(200);
        engine.publishSnapshot(second);
        engine.applyChanges(handler);
        assertEquals("strategy 7;listing 200;", handler.events.toString());
    }

    @Test
    void testApplyChangesReportsBlockingEverythingOnceAndNotItsParts() {
        final RiskEngine engine = new RiskEngine();
        final RecordingKillHandler handler = new RecordingKillHandler();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.globalKill = true;
        killed.killedStrategies.add(7);
        engine.publishSnapshot(killed);
        engine.applyChanges(handler);

        // Lifting the global kill leaves strategy 7 killed, but its orders were already cancelled.
        final RiskEngineSnapshot partial = new RiskEngineSnapshot();
        partial.killedStrategies.add(7);
        engine.publishSnapshot(partial);
        engine.applyChanges(handler);

        assertEquals("everything;", handler.events.toString());
    }

    @Test
    void testResumingAScopeNeedsNoAction() {
        final RiskEngine engine = new RiskEngine();
        final RecordingKillHandler handler = new RecordingKillHandler();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategies.add(7);
        engine.publishSnapshot(killed);
        engine.applyChanges(handler);

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(handler);

        assertEquals("strategy 7;", handler.events.toString());
        assertTrue(engine.check(order, positions, orders, 7, 1));
    }

    // --- latch ---

    @Test
    void testLatchHaltsUntilTheRegistryHoldsTheKill() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.latch(7));
        assertFalse(engine.latch(7), "already halted");
        assertFalse(engine.check(order, positions, orders, 7, 1));
        assertEquals(List.of(7), RiskSnapshots.drainLatchedHalts(engine));
        assertEquals(List.of(), RiskSnapshots.drainLatchedHalts(engine), "each halt is handed over once");

        final RiskEngineSnapshot confirmed = new RiskEngineSnapshot();
        confirmed.killedStrategies.add(7);
        engine.publishSnapshot(confirmed);
        engine.applyChanges(new RecordingKillHandler());
        assertFalse(engine.isLatched(7));
        assertFalse(engine.check(order, positions, orders, 7, 1), "now held by the registry kill");

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(new RecordingKillHandler());
        assertTrue(engine.check(order, positions, orders, 7, 1), "the operator resumed it");
    }

    @Test
    void testConfirmedHaltReleasesTheLatchEvenIfAlreadyResumed() {
        final RiskEngine engine = new RiskEngine();
        engine.latch(7);

        // The registry took the halt, but an operator resumed it before any snapshot showed the kill.
        final RiskEngineSnapshot confirmed = new RiskEngineSnapshot();
        confirmed.confirmedHalts.add(7);
        engine.publishSnapshot(confirmed);
        engine.applyChanges(new RecordingKillHandler());

        assertFalse(engine.isLatched(7));
        assertTrue(engine.check(order, positions, orders, 7, 1));
    }

    @Test
    void testLatchSurvivesAGlobalKillLifting() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.globalKill = true;
        engine.publishSnapshot(killed);
        assertTrue(engine.latch(7), "a global kill doesn't hold this strategy's breach");

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(new RecordingKillHandler());
        assertFalse(engine.check(order, positions, orders, 7, 1));
    }

    @Test
    void testLatchIsNotNeededWhenTheStrategyIsAlreadyKilled() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategies.add(7);
        engine.publishSnapshot(killed);

        assertFalse(engine.latch(7));
        assertEquals(List.of(), RiskSnapshots.drainLatchedHalts(engine));
    }

    // --- check() ---

    @Test
    void testCheckReturnsTrueWithNoPolicies() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.check(order, positions, orders, 0, 0));
    }

    @Test
    void testCheckReturnsFalseWhenGlobalOrderPolicyViolated() {
        final OrderPolicyGroup globalOrder = buildOrderGroup(new MaxOrderSizePolicy(0));
        final MarketPolicyGroup globalMarket = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);
        assertFalse(engine.check(order, positions, orders, 0, 0));
    }

    @Test
    void testCheckReturnsTrueWhenGlobalOrderPolicyNotViolated() {
        final OrderPolicyGroup globalOrder = buildOrderGroup(new MaxOrderSizePolicy(1000));
        final MarketPolicyGroup globalMarket = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);
        order.encoder.size(5);
        assertTrue(engine.check(order, positions, orders, 0, 0));
    }

    @Test
    void testCheckReturnsFalseWhenAnyPolicyInGlobalGroupViolated() {
        // Two policies: the first passes, the second rejects — AND logic
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        globalOrder.policies[0] = new MaxOrderSizePolicy(1000);
        globalOrder.policies[1] = new MaxOrderSizePolicy(0);
        globalOrder.count = 2;

        final MarketPolicyGroup globalMarket = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);
        order.encoder.size(5);
        assertFalse(engine.check(order, positions, orders, 0, 0));
    }

    @Test
    void testCheckEvaluatesStrategyGroupFromPublishedSnapshot() {
        final RiskEngine engine = new RiskEngine();

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        final OrderPolicyGroup stratGroup = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        stratGroup.policies[0] = new MaxOrderSizePolicy(0);
        stratGroup.count = 1;
        snapshot.strategyOrderGroups.put(7, stratGroup);
        engine.publishSnapshot(snapshot);

        assertFalse(engine.check(order, positions, orders, 7, 0));
        assertTrue(engine.check(order, positions, orders, 8, 0)); // different strategy — passes
    }

    @Test
    void testCheckEvaluatesListingGroupFromPublishedSnapshot() {
        final RiskEngine engine = new RiskEngine();

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        final OrderPolicyGroup listingGroup = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        listingGroup.policies[0] = new MaxOrderSizePolicy(0);
        listingGroup.count = 1;
        snapshot.listingOrderGroups.put(200, listingGroup);
        engine.publishSnapshot(snapshot);

        assertFalse(engine.check(order, positions, orders, 0, 200));
        assertTrue(engine.check(order, positions, orders, 0, 201)); // different listing — passes
    }

    // --- checkMarketPolicies() ---

    @Test
    void testCheckMarketPoliciesReturnsFalseWithNoPolicies() {
        final RiskEngine engine = new RiskEngine();
        assertFalse(engine.checkMarketPolicies(0, 0, positions, orders));
    }

    @Test
    void testCheckMarketPoliciesReturnsTrueWhenGlobalMarketPolicyViolated() {
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final MarketPolicyGroup globalMarket =
                buildMarketGroup(new MaxTotalPnlLossPolicy(new SharedPriceBuffer(1), new PriceSlotRegistry(1), 100L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -200L;

        assertTrue(engine.checkMarketPolicies(1, 100, positions, orders));
    }

    @Test
    void testCheckMarketPoliciesReturnsFalseWhenNoPolicyViolated() {
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final MarketPolicyGroup globalMarket =
                buildMarketGroup(new MaxTotalPnlLossPolicy(new SharedPriceBuffer(1), new PriceSlotRegistry(1), 1000L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -50L;

        assertFalse(engine.checkMarketPolicies(1, 100, positions, orders));
    }

    @Test
    void testStrategyScopedLossLimitJudgesTheStrategyTotal() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.strategyMarketGroups.put(
                1,
                buildMarketGroup(new MaxTotalPnlLossPolicy(new SharedPriceBuffer(1), new PriceSlotRegistry(1), 100L)));
        engine.publishSnapshot(snapshot);
        realize(1, 100, -60L);
        realize(1, 200, -60L);

        assertTrue(engine.checkMarketPolicies(1, 100, positions, orders));
        assertTrue(engine.hasMarketPolicies());
    }

    @Test
    void testGlobalLossLimitJudgesEachStrategyTotalSeparately() {
        final RiskEngine engine = new RiskEngine(
                new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP),
                buildMarketGroup(new MaxTotalPnlLossPolicy(new SharedPriceBuffer(1), new PriceSlotRegistry(1), 100L)));
        realize(1, 100, -60L);
        realize(2, 100, -60L);

        assertFalse(engine.checkMarketPolicies(1, 100, positions, orders));
        assertFalse(engine.checkMarketPolicies(2, 100, positions, orders));
        realize(2, 200, -60L);
        assertTrue(engine.checkMarketPolicies(2, 100, positions, orders));
    }

    @Test
    void testListingScopedLossLimitJudgesOnlyItsPosition() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.listingMarketGroups.put(
                100,
                buildMarketGroup(new MaxTotalPnlLossPolicy(new SharedPriceBuffer(1), new PriceSlotRegistry(1), 100L)));
        engine.publishSnapshot(snapshot);
        realize(1, 100, -60L);
        realize(1, 200, -500L);

        assertFalse(engine.checkMarketPolicies(1, 100, positions, orders));
        realize(1, 100, -150L);
        assertTrue(engine.checkMarketPolicies(1, 100, positions, orders));
    }

    @Test
    void testHasMarketPoliciesIsFalseWithOnlyOrderPolicies() {
        assertFalse(new RiskEngine().hasMarketPolicies());
    }

    @Test
    void testAStrategyIsHaltedForBreachesOnlyByItsOwnKillOrALatch() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.globalKill = true;
        snapshot.killedStrategies.add(7);
        engine.publishSnapshot(snapshot);

        assertTrue(engine.isStrategyHalted(7));
        assertFalse(engine.isStrategyHalted(8));
        engine.latch(9);
        assertTrue(engine.isStrategyHalted(9));
    }

    private void realize(final int strategyId, final int listingId, final long realizedPnl) {
        if (positions.getStrategyPosition(strategyId, listingId) == null) {
            positions.applyStrategyFill(strategyId, listingId, Side.Bid, 1, 100, 0);
        }
        positions.getStrategyPosition(strategyId, listingId).realizedPnl = realizedPnl;
    }

    // --- publishSnapshot ---

    @Test
    void testPublishSnapshotUpdatesEngineState() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.check(order, positions, orders, 0, 0)); // empty snapshot — passes

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.globalOrderGroup.policies[0] = new MaxOrderSizePolicy(0);
        snapshot.globalOrderGroup.count = 1;
        engine.publishSnapshot(snapshot);

        assertFalse(engine.check(order, positions, orders, 0, 0)); // now blocked
    }

    // --- Helpers ---

    private static OrderPolicyGroup buildOrderGroup(final OrderRiskPolicy policy) {
        final OrderPolicyGroup group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        group.policies[0] = policy;
        group.count = 1;
        return group;
    }

    private static MarketPolicyGroup buildMarketGroup(final MarketRiskPolicy policy) {
        final MarketPolicyGroup group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        group.policies[0] = policy;
        group.count = 1;
        return group;
    }

    private static final class RecordingKillHandler implements RiskEngine.KillHandler {
        final StringBuilder events = new StringBuilder();

        @Override
        public void onEverythingKilled() {
            events.append("everything;");
        }

        @Override
        public void onStrategyKilled(final int strategyId) {
            events.append("strategy ").append(strategyId).append(';');
        }

        @Override
        public void onListingKilled(final int listingId) {
            events.append("listing ").append(listingId).append(';');
        }
    }
}
