package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.policy.AutoDenyPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxPnlLossPolicy;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
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

    // --- isStrategyHalted / haltStrategy / resumeStrategy ---

    @Test
    void testIsStrategyHaltedReturnsFalseByDefault() {
        final RiskEngine engine = new RiskEngine();
        assertFalse(engine.isStrategyHalted(0));
    }

    @Test
    void testHaltAndResumeStrategy() {
        final RiskEngine engine = new RiskEngine();
        engine.haltStrategy(5);
        assertTrue(engine.isStrategyHalted(5));
        engine.resumeStrategy(5);
        assertFalse(engine.isStrategyHalted(5));
    }

    @Test
    void testIsStrategyHaltedReturnsFalseForNegativeId() {
        final RiskEngine engine = new RiskEngine();
        engine.haltStrategy(-1); // no-op
        assertFalse(engine.isStrategyHalted(-1));
    }

    @Test
    void testIsStrategyHaltedReturnsFalseForIdAtUpperBoundary() {
        final RiskEngine engine = new RiskEngine();
        engine.haltStrategy(1024); // no-op — out of range
        assertFalse(engine.isStrategyHalted(1024));
    }

    @Test
    void testHaltStrategyMaxValidId() {
        final RiskEngine engine = new RiskEngine();
        engine.haltStrategy(1023);
        assertTrue(engine.isStrategyHalted(1023));
    }

    // --- check() ---

    @Test
    void testCheckReturnsFalseWhenStrategyHalted() {
        final RiskEngine engine = new RiskEngine();
        engine.haltStrategy(3);
        assertFalse(engine.check(order, positions, orders, 3, 0));
    }

    @Test
    void testCheckReturnsTrueWithNoPolicies() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.check(order, positions, orders, 0, 0));
    }

    @Test
    void testCheckReturnsFalseWhenGlobalOrderPolicyViolated() {
        final OrderPolicyGroup globalOrder = buildOrderGroup(new AutoDenyPolicy());
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
        // Two policies: MaxOrderSizePolicy passes, KillSwitch fails — AND logic
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        globalOrder.policies[0] = new MaxOrderSizePolicy(1000);
        globalOrder.policies[1] = new AutoDenyPolicy();
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
        stratGroup.policies[0] = new AutoDenyPolicy();
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
        listingGroup.policies[0] = new AutoDenyPolicy();
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
        final MarketPolicyGroup globalMarket = buildMarketGroup(new MaxPnlLossPolicy(100L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -200L;

        assertTrue(engine.checkMarketPolicies(1, 100, positions, orders));
    }

    @Test
    void testCheckMarketPoliciesReturnsFalseWhenNoPolicyViolated() {
        final OrderPolicyGroup globalOrder = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        final MarketPolicyGroup globalMarket = buildMarketGroup(new MaxPnlLossPolicy(1000L));
        final RiskEngine engine = new RiskEngine(globalOrder, globalMarket);

        positions.applyStrategyFill(1, 100, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(1, 100).realizedPnl = -50L;

        assertFalse(engine.checkMarketPolicies(1, 100, positions, orders));
    }

    // --- publishSnapshot ---

    @Test
    void testPublishSnapshotUpdatesEngineState() {
        final RiskEngine engine = new RiskEngine();
        assertTrue(engine.check(order, positions, orders, 0, 0)); // empty snapshot — passes

        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.globalOrderGroup.policies[0] = new AutoDenyPolicy();
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
}
