package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.RejectReason;
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

    private static final int EXCHANGE_ID = 3;
    private static final int OTHER_EXCHANGE_ID = 4;

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
        final RiskEngine engine =
                RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), ListingDirectory.EMPTY, 1);
        assertNotNull(engine.check(order, positions, orders, 1, 1, EXCHANGE_ID));
        assertTrue(engine.isBlocked(1, 1, EXCHANGE_ID));

        engine.recordRefresh(now[0]);
        engine.applyChanges(new RecordingKillHandler());
        assertNull(engine.check(order, positions, orders, 1, 1, EXCHANGE_ID));
    }

    @Test
    void testStaleRiskBlocksNewOrdersWithoutKillingAnything() {
        final long[] now = {1_000};
        final RiskEngine engine =
                RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), ListingDirectory.EMPTY, 1);
        final RecordingKillHandler handler = new RecordingKillHandler();
        engine.recordRefresh(now[0]);
        engine.applyChanges(handler);

        now[0] += 31_000;
        engine.applyChanges(handler);
        assertTrue(engine.isBlocked(1, 1, EXCHANGE_ID));
        assertFalse(engine.hasKills(), "a stale view is not a kill: resting orders stay");

        engine.recordRefresh(now[0]);
        engine.applyChanges(handler);
        assertFalse(engine.isBlocked(1, 1, EXCHANGE_ID));
        assertEquals("", handler.events.toString());
    }

    @Test
    void testKillScopesBlockOnlyWhatTheyCover() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.killedStrategies.add(7);
        snapshot.killedListings.add(200);
        engine.publishSnapshot(snapshot);

        assertNotNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
        assertNotNull(engine.check(order, positions, orders, 1, 200, EXCHANGE_ID));
        assertNull(engine.check(order, positions, orders, 8, 201, EXCHANGE_ID));
    }

    @Test
    void testAHaltSaysWhyAndALimitNamesItsPolicy() {
        final long[] now = {1_000};
        final RiskEngine engine =
                RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), ListingDirectory.EMPTY, 1);
        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
        assertEquals(RiskEngine.HALT_STALE, engine.haltCause());

        engine.recordRefresh(now[0]);
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.killedListings.add(200);
        snapshot.addPolicy(7, 0, 0, new MaxOrderSizePolicy(0), 37);
        engine.publishSnapshot(snapshot);
        engine.applyChanges(new RecordingKillHandler());

        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 8, 200, EXCHANGE_ID));
        assertEquals(RiskEngine.HALT_KILLED, engine.haltCause());

        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
        assertEquals(37, engine.violatedPolicyId());

        engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);
        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
        assertEquals(RiskEngine.HALT_LATCHED, engine.haltCause());
    }

    @Test
    void testGlobalKillBlocksEverything() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.globalKill = true;
        engine.publishSnapshot(killed);
        assertNotNull(engine.check(order, positions, orders, 8, 201, EXCHANGE_ID));
    }

    @Test
    void testEveryKilledOrLatchedScopeIsReportedForAResweep() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategies.add(7);
        killed.killedListings.add(200);
        engine.publishSnapshot(killed);
        engine.applyChanges(new RecordingKillHandler());
        engine.latch(9, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        final RecordingKillHandler handler = new RecordingKillHandler();
        engine.forEachKilledScope(handler);

        assertEquals("strategy 7;strategy 9;listing 200;", handler.events.toString());
    }

    @Test
    void testAStrategyListingKillIsReportedOnceWhenNewAndOnEveryResweep() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategyListings.add(RiskEngineSnapshot.pairKey(7, 200));
        engine.publishSnapshot(killed);

        final RecordingKillHandler applied = new RecordingKillHandler();
        engine.applyChanges(applied);
        engine.applyChanges(applied);
        assertEquals("pair 7/200;", applied.events.toString());

        final RecordingKillHandler resweep = new RecordingKillHandler();
        engine.forEachKilledScope(resweep);
        assertEquals("pair 7/200;", resweep.events.toString());
        assertTrue(engine.hasKills());
        assertNotNull(engine.check(order, positions, orders, 7, 200, EXCHANGE_ID));
        assertNull(engine.check(order, positions, orders, 7, 201, EXCHANGE_ID));
    }

    @Test
    void testThePairKeyRoundTripsLargeAndUnusualIds() {
        final long key = RiskEngineSnapshot.pairKey(Integer.MAX_VALUE, Integer.MIN_VALUE + 1);
        assertEquals(Integer.MAX_VALUE, RiskEngineSnapshot.pairStrategy(key));
        assertEquals(Integer.MIN_VALUE + 1, RiskEngineSnapshot.pairScope(key));
        assertNotEquals(RiskEngineSnapshot.pairKey(1, 2), RiskEngineSnapshot.pairKey(2, 1));
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
        assertNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
    }

    // --- latch ---

    @Test
    void testLatchHaltsUntilTheRegistryHoldsTheKill() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0));
        assertFalse(engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0), "already halted");
        assertNotNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
        assertEquals(List.of(7), RiskSnapshots.drainLatchedHalts(engine));
        assertEquals(List.of(), RiskSnapshots.drainLatchedHalts(engine), "each halt is handed over once");

        final RiskEngineSnapshot confirmed = new RiskEngineSnapshot();
        confirmed.killedStrategies.add(7);
        engine.publishSnapshot(confirmed);
        engine.applyChanges(new RecordingKillHandler());
        assertFalse(engine.isLatchedAt(7, 0, 0));
        assertNotNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID), "now held by the registry kill");

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(new RecordingKillHandler());
        assertNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID), "the operator resumed it");
    }

    @Test
    void testConfirmedHaltReleasesTheLatchEvenIfAlreadyResumed() {
        final RiskEngine engine = new RiskEngine();
        engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        // The registry took the halt, but an operator resumed it before any snapshot showed the kill.
        final RiskEngineSnapshot confirmed = new RiskEngineSnapshot();
        confirmed.confirmedStrategyHalts.add(7);
        engine.publishSnapshot(confirmed);
        engine.applyChanges(new RecordingKillHandler());

        assertFalse(engine.isLatchedAt(7, 0, 0));
        assertNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
    }

    @Test
    void testLatchSurvivesAGlobalKillLifting() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.globalKill = true;
        engine.publishSnapshot(killed);
        assertTrue(
                engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0),
                "a global kill doesn't hold this strategy's breach");

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(new RecordingKillHandler());
        assertNotNull(engine.check(order, positions, orders, 7, 1, EXCHANGE_ID));
    }

    @Test
    void testLatchIsNotNeededWhenTheStrategyIsAlreadyKilled() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot killed = new RiskEngineSnapshot();
        killed.killedStrategies.add(7);
        engine.publishSnapshot(killed);

        assertFalse(engine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0));
        assertEquals(List.of(), RiskSnapshots.drainLatchedHalts(engine));
    }

    // --- check() ---

    @Test
    void testCheckReturnsTrueWithNoPolicies() {
        final RiskEngine engine = new RiskEngine();
        assertNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID));
    }

    @Test
    void testCheckReturnsFalseWhenGlobalOrderPolicyViolated() {
        final OrderPolicyGroup globalOrder = buildOrderGroup(new MaxOrderSizePolicy(0));
        final MarketPolicyGroup globalMarket = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);
        assertNotNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID));
    }

    @Test
    void testCheckReturnsTrueWhenGlobalOrderPolicyNotViolated() {
        final OrderPolicyGroup globalOrder = buildOrderGroup(new MaxOrderSizePolicy(1000));
        final MarketPolicyGroup globalMarket = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);
        order.encoder.size(5);
        assertNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID));
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
        assertNotNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID));
    }

    @Test
    void testCheckEvaluatesStrategyGroupFromPublishedSnapshot() {
        final RiskEngine engine = new RiskEngine();

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(7, 0, 0, new MaxOrderSizePolicy(0), 0);
        engine.publishSnapshot(snapshot);

        assertNotNull(engine.check(order, positions, orders, 7, 0, EXCHANGE_ID));
        assertNull(engine.check(order, positions, orders, 8, 0, EXCHANGE_ID)); // different strategy — passes
    }

    @Test
    void testCheckEvaluatesListingGroupFromPublishedSnapshot() {
        final RiskEngine engine = new RiskEngine();

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(0, 200, 0, new MaxOrderSizePolicy(0), 0);
        engine.publishSnapshot(snapshot);

        assertNotNull(engine.check(order, positions, orders, 0, 200, EXCHANGE_ID));
        assertNull(engine.check(order, positions, orders, 0, 201, EXCHANGE_ID)); // different listing — passes
    }

    // --- checkMarketPolicies() ---

    @Test
    void testCheckMarketPoliciesReturnsFalseWithNoPolicies() {
        final RiskEngine engine = new RiskEngine();
        assertFalse(engine.checkMarketPolicies(0, 0, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testCheckMarketPoliciesReturnsTrueWhenGlobalMarketPolicyViolated() {
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final MarketPolicyGroup globalMarket = buildMarketGroup(new MaxTotalPnlLossPolicy(
                new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -200L;

        assertTrue(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testABreachNamesThePolicyThatTripped() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                1,
                0,
                0,
                new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L),
                12);
        engine.publishSnapshot(snapshot);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -200L;

        assertTrue(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
        assertEquals(12, engine.breachedPolicyId());
    }

    @Test
    void testCheckMarketPoliciesReturnsFalseWhenNoPolicyViolated() {
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final MarketPolicyGroup globalMarket = buildMarketGroup(new MaxTotalPnlLossPolicy(
                new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 1000L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -50L;

        assertFalse(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testStrategyScopedLossLimitJudgesTheStrategyTotal() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                1,
                0,
                0,
                new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L),
                0);
        engine.publishSnapshot(snapshot);
        realize(1, 100, -60L);
        realize(1, 200, -60L);

        assertTrue(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
        assertTrue(engine.hasMarketPolicies());
    }

    @Test
    void testGlobalLossLimitJudgesEachStrategyTotalSeparately() {
        final RiskEngine engine = new RiskEngine(
                new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP),
                buildMarketGroup(new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L)));
        realize(1, 100, -60L);
        realize(2, 100, -60L);

        assertFalse(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
        assertFalse(engine.checkMarketPolicies(2, 100, EXCHANGE_ID, positions, orders));
        realize(2, 200, -60L);
        assertTrue(engine.checkMarketPolicies(2, 100, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testListingScopedLossLimitJudgesOnlyItsPosition() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                0,
                100,
                0,
                new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 100, 0, 100L),
                0);
        engine.publishSnapshot(snapshot);
        realize(1, 100, -60L);
        realize(1, 200, -500L);

        assertFalse(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
        realize(1, 100, -150L);
        assertTrue(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
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
        engine.latch(9, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);
        assertTrue(engine.isStrategyHalted(9));
    }

    // --- exchange scope ---

    @Test
    void testAnExchangeKillBlocksEveryStrategyThereAndNothingElsewhere() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addKill(0, 0, EXCHANGE_ID);
        snapshot.addKill(7, 0, OTHER_EXCHANGE_ID);
        engine.publishSnapshot(snapshot);

        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 1, 100, EXCHANGE_ID));
        assertTrue(engine.isHalted(8, 100, EXCHANGE_ID));
        assertTrue(engine.isHalted(7, 200, OTHER_EXCHANGE_ID));
        assertFalse(engine.isHalted(8, 200, OTHER_EXCHANGE_ID), "the strategy+exchange kill is 7's alone");
    }

    @Test
    void testExchangeKillsAreReportedWhenNewAndOnEveryResweep() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addKill(0, 0, EXCHANGE_ID);
        snapshot.addKill(7, 0, OTHER_EXCHANGE_ID);
        engine.publishSnapshot(snapshot);

        final RecordingKillHandler applied = new RecordingKillHandler();
        engine.applyChanges(applied);
        engine.applyChanges(applied);
        assertEquals("exchange 3;strategy-exchange 7/4;", applied.events.toString());

        final RecordingKillHandler resweep = new RecordingKillHandler();
        engine.forEachKilledScope(resweep);
        assertEquals("exchange 3;strategy-exchange 7/4;", resweep.events.toString());
    }

    @Test
    void testAnExchangePolicyJudgesOnlyOrdersOnThatExchange() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(0, 0, EXCHANGE_ID, new MaxOrderSizePolicy(0), 21);
        snapshot.addPolicy(7, 0, OTHER_EXCHANGE_ID, new MaxOrderSizePolicy(0), 22);
        engine.publishSnapshot(snapshot);

        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, engine.check(order, positions, orders, 1, 100, EXCHANGE_ID));
        assertEquals(21, engine.violatedPolicyId());
        assertNull(engine.check(order, positions, orders, 1, 200, OTHER_EXCHANGE_ID));
        assertEquals(
                RejectReason.RISK_LIMIT_EXCEEDED, engine.check(order, positions, orders, 7, 200, OTHER_EXCHANGE_ID));
        assertEquals(22, engine.violatedPolicyId());
    }

    @Test
    void testOrderGroupsAreJudgedGlobalThenExchangeThenStrategyThenStrategyExchangeThenPairThenListing() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(0, 100, 0, new MaxOrderSizePolicy(0), 6);
        snapshot.addPolicy(7, 100, 0, new MaxOrderSizePolicy(0), 5);
        snapshot.addPolicy(7, 0, EXCHANGE_ID, new MaxOrderSizePolicy(0), 4);
        snapshot.addPolicy(7, 0, 0, new MaxOrderSizePolicy(0), 3);
        snapshot.addPolicy(0, 0, EXCHANGE_ID, new MaxOrderSizePolicy(0), 2);
        snapshot.addPolicy(0, 0, 0, new MaxOrderSizePolicy(0), 1);
        engine.publishSnapshot(snapshot);

        engine.check(order, positions, orders, 7, 100, EXCHANGE_ID);
        assertEquals(1, engine.violatedPolicyId());
        engine.check(order, positions, orders, 7, 100, OTHER_EXCHANGE_ID);
        assertEquals(1, engine.violatedPolicyId());
    }

    @Test
    void testMarketGroupsOnAnExchangeAreChecked() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                0,
                0,
                EXCHANGE_ID,
                new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L),
                31);
        engine.publishSnapshot(snapshot);
        realize(1, 100, -200L);

        assertFalse(engine.checkMarketPolicies(1, 100, OTHER_EXCHANGE_ID, positions, orders));
        assertTrue(engine.checkMarketPolicies(1, 100, EXCHANGE_ID, positions, orders));
        assertEquals(31, engine.breachedPolicyId());
        assertTrue(engine.hasMarketPolicies());
    }

    // --- unguarded ---

    @Test
    void testAnUnguardedSessionStopsEverythingAndSaysWhy() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot unguarded = new RiskEngineSnapshot();
        unguarded.unguarded = true;
        engine.publishSnapshot(unguarded);

        final RecordingKillHandler handler = new RecordingKillHandler();
        engine.applyChanges(handler);
        engine.publishSnapshot(unguarded);
        engine.applyChanges(handler);
        assertEquals("everything;", handler.events.toString());
        assertTrue(engine.hasKills());

        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 1, 100, EXCHANGE_ID));
        assertEquals(RiskEngine.HALT_UNGUARDED, engine.haltCause());
        assertTrue(engine.isHalted(1, 100, EXCHANGE_ID));

        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(handler);
        assertNull(engine.check(order, positions, orders, 1, 100, EXCHANGE_ID), "the policies were added");
    }

    // --- scoped latches ---

    @Test
    void testAStrategyExchangeLatchHaltsOnlyThatExchange() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.latch(7, EXCHANGE_ID, 0, RiskEngine.CAUSE_REJECT_STREAK, 31));
        assertFalse(engine.latch(7, EXCHANGE_ID, 0, RiskEngine.CAUSE_REJECT_STREAK, 31), "already halted");

        assertEquals(RejectReason.HALTED, engine.check(order, positions, orders, 7, 100, EXCHANGE_ID));
        assertEquals(RiskEngine.HALT_LATCHED, engine.haltCause());
        assertNull(engine.check(order, positions, orders, 7, 200, OTHER_EXCHANGE_ID));
        assertNull(engine.check(order, positions, orders, 8, 100, EXCHANGE_ID));
        assertFalse(engine.isStrategyHalted(7), "only one exchange is halted");
        assertEquals(List.of("7/3/0 cause 1 policy 31 rejects 0"), RiskSnapshots.drainLatchedHaltScopes(engine));

        final RecordingKillHandler resweep = new RecordingKillHandler();
        engine.forEachKilledScope(resweep);
        assertEquals("strategy-exchange 7/3;", resweep.events.toString());
    }

    @Test
    void testAStrategyListingLatchHaltsOnlyThatListing() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.latch(7, 0, 100, RiskEngine.CAUSE_MARKET_LIMIT, 12));

        assertTrue(engine.isHalted(7, 100, EXCHANGE_ID));
        assertFalse(engine.isHalted(7, 101, EXCHANGE_ID));
        assertTrue(engine.isLatchedAt(7, 0, 100));
        assertFalse(engine.isLatchedAt(7, 0, 0));

        final RecordingKillHandler resweep = new RecordingKillHandler();
        engine.forEachKilledScope(resweep);
        assertEquals("pair 7/100;", resweep.events.toString());
    }

    @Test
    void testAScopedLatchIsReleasedByAKillOrConfirmationOnTheSameScope() {
        final RiskEngine engine = new RiskEngine();
        engine.latch(7, EXCHANGE_ID, 0, RiskEngine.CAUSE_REJECT_STREAK, 0);
        engine.latch(7, 0, 100, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        final RiskEngineSnapshot wider = new RiskEngineSnapshot();
        wider.addKill(7, 0, 0);
        engine.publishSnapshot(wider);
        engine.applyChanges(new RecordingKillHandler());
        assertTrue(engine.isLatchedAt(7, EXCHANGE_ID, 0), "a strategy kill doesn't record an exchange halt");

        final RiskEngineSnapshot held = new RiskEngineSnapshot();
        held.addKill(7, 0, EXCHANGE_ID);
        held.confirmedStrategyListingHalts.add(RiskEngineSnapshot.pairKey(7, 100));
        engine.publishSnapshot(held);
        engine.applyChanges(new RecordingKillHandler());
        assertFalse(engine.isLatchedAt(7, EXCHANGE_ID, 0));
        assertFalse(engine.isLatchedAt(7, 0, 100));
        assertFalse(engine.latch(7, EXCHANGE_ID, 0, RiskEngine.CAUSE_REJECT_STREAK, 0), "the kill holds it already");
    }

    @Test
    void testABreachLatchesInItsPolicysScope() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                0, 0, EXCHANGE_ID, new MaxConsecutiveRejectsPolicy(engine.rejectStreaks(), 0, EXCHANGE_ID, 2), 41);
        snapshot.addPolicy(0, 200, 0, new MaxConsecutiveRejectsPolicy(engine.rejectStreaks(), 200, 0, 2), 42);
        engine.publishSnapshot(snapshot);
        final RejectStreaks streaks = engine.rejectStreaks();

        streaks.recordReject(7, EXCHANGE_ID, 100);
        streaks.recordReject(7, EXCHANGE_ID, 101);
        assertTrue(engine.checkMarketPolicies(7, 100, EXCHANGE_ID, positions, orders));
        assertEquals(RiskEngine.CAUSE_REJECT_STREAK, engine.breachedCause());
        assertTrue(engine.latchBreach(7, 0));
        assertEquals(EXCHANGE_ID, engine.latchExchangeId());
        assertEquals(0, engine.latchListingId());
        assertEquals(0, streaks.ofExchange(7, EXCHANGE_ID), "the latch ends the streak");

        streaks.recordReject(7, OTHER_EXCHANGE_ID, 200);
        streaks.recordReject(7, OTHER_EXCHANGE_ID, 200);
        assertTrue(engine.checkMarketPolicies(7, 200, OTHER_EXCHANGE_ID, positions, orders));
        assertTrue(engine.latchBreach(7, 0));
        assertEquals(0, engine.latchExchangeId());
        assertEquals(200, engine.latchListingId());
        assertEquals(
                List.of("7/3/0 cause 1 policy 41 rejects 2", "7/0/200 cause 1 policy 42 rejects 2"),
                RiskSnapshots.drainLatchedHaltScopes(engine));
    }

    @Test
    void testAnInsufficientFundsStreakLatchesTheStrategyOnThatExchangeWhateverThePolicysScope() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(0, 0, 0, new MaxConsecutiveRejectsPolicy(engine.rejectStreaks(), 0, 0, 1), 51);
        engine.publishSnapshot(snapshot);

        engine.rejectStreaks().recordReject(7, EXCHANGE_ID, 100);
        assertTrue(engine.checkMarketPolicies(7, 100, EXCHANGE_ID, positions, orders));
        assertTrue(engine.latchBreach(7, EXCHANGE_ID));

        assertTrue(engine.isLatchedAt(7, EXCHANGE_ID, 0));
        assertFalse(engine.isStrategyHalted(7), "its other venues trade on");
        assertNull(engine.check(order, positions, orders, 7, 200, OTHER_EXCHANGE_ID));
    }

    @Test
    void testALossBreachIsNotNarrowedByAFundsReject() {
        final RiskEngine engine = new RiskEngine();
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.addPolicy(
                0,
                0,
                0,
                new MaxTotalPnlLossPolicy(
                        new SharedPriceBuffer(1), new PriceSlotRegistry(1), ListingDirectory.EMPTY, 0, 0, 100L),
                61);
        engine.publishSnapshot(snapshot);
        realize(7, 100, -200L);

        assertTrue(engine.checkMarketPolicies(7, 100, EXCHANGE_ID, positions, orders));
        assertEquals(RiskEngine.CAUSE_MARKET_LIMIT, engine.breachedCause());
        assertTrue(engine.latchBreach(7, EXCHANGE_ID));
        assertTrue(engine.isLatchedAt(7, 0, 0));
    }

    // --- long-only listings ---

    @Test
    void testOnALongOnlyListingAStrategyMaySellOnlyWhatItHoldsLessWhatItIsSelling() {
        final ListingDirectory directory = TestListings.directory(TestListings.longOnlyBinary(100, EXCHANGE_ID));
        final RiskEngine engine = RiskEngine.withScopedPolicies(List.of(), directory, new RejectStreaks());
        order.encoder.side(Side.Ask).size(5);
        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, engine.check(order, positions, orders, 7, 100, EXCHANGE_ID));
        assertEquals(0, engine.violatedPolicyId(), "built in, no registry policy");

        positions.applyStrategyFill(7, 100, Side.Bid, 8, 100, 0);
        assertNull(engine.check(order, positions, orders, 7, 100, EXCHANGE_ID));
        positions.adjustStrategyLeaves(7, 100, Side.Ask, 4, 0);
        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, engine.check(order, positions, orders, 7, 100, EXCHANGE_ID));

        order.encoder.side(Side.Bid).size(50);
        assertNull(engine.check(order, positions, orders, 7, 100, EXCHANGE_ID), "buying is never limited here");
        order.encoder.side(Side.Ask).size(50);
        assertNull(engine.check(order, positions, orders, 7, 200, EXCHANGE_ID), "another listing may go short");
    }

    @Test
    void testALongOnlySellModifyIsJudgedAsTheReplacementForItsOrder() {
        final ListingDirectory directory = TestListings.directory(TestListings.longOnlyBinary(100, EXCHANGE_ID));
        final RiskEngine engine = RiskEngine.withScopedPolicies(List.of(), directory, new RejectStreaks());
        final PooledOrderStateManager tracked = new PooledOrderStateManager(4);
        positions.applyStrategyFill(7, 100, Side.Bid, 8, 100, 0);
        final Order working = new Order();
        working.encodeClientOid(5, 7);
        working.encoder.side(Side.Ask).size(6).price(100);
        tracked.trackOrder(working);
        positions.adjustStrategyLeaves(7, 100, Side.Ask, 6, 0);

        final Order modify = new Order();
        modify.encodeClientOid(5, 7);
        modify.encoder.side(Side.Ask).size(8).price(100);
        assertNull(engine.check(modify, positions, tracked, 7, 100, EXCHANGE_ID));
        modify.encoder.size(9);
        assertEquals(RejectReason.RISK_LIMIT_EXCEEDED, engine.check(modify, positions, tracked, 7, 100, EXCHANGE_ID));
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
        assertNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID)); // empty snapshot — passes

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.orderGroups.global.add(new MaxOrderSizePolicy(0), 0);
        engine.publishSnapshot(snapshot);

        assertNotNull(engine.check(order, positions, orders, 0, 0, EXCHANGE_ID)); // now blocked
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
        group.add(policy, 0);
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
        public void onExchangeKilled(final int exchangeId) {
            events.append("exchange ").append(exchangeId).append(';');
        }

        @Override
        public void onListingKilled(final int listingId) {
            events.append("listing ").append(listingId).append(';');
        }

        @Override
        public void onStrategyExchangeKilled(final int strategyId, final int exchangeId) {
            events.append("strategy-exchange ")
                    .append(strategyId)
                    .append('/')
                    .append(exchangeId)
                    .append(';');
        }

        @Override
        public void onStrategyListingKilled(final int strategyId, final int listingId) {
            events.append("pair ")
                    .append(strategyId)
                    .append('/')
                    .append(listingId)
                    .append(';');
        }
    }
}
