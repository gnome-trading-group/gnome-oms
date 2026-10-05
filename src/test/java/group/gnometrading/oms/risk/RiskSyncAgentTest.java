package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.risk.PolicyScope;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.risk.RiskPolicyRecord;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.ViewString;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskSyncAgentTest {

    private static final int STRATEGY_ID = 1;
    private static final int LISTING_ID = 100;
    private static final Duration INTERVAL = Duration.ofMillis(1);
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);

    @Mock
    private RiskMaster riskMaster;

    private final long[] now = {0};
    private final EpochClock clock = () -> now[0];

    @Mock
    private OrderStateManager orders;

    private RiskEngine riskEngine;
    private RiskSyncAgent agent;
    private DefaultPositionTracker positions;
    private Order order;

    @BeforeEach
    void setUp() {
        riskEngine = RiskEngine.syncedFromRegistry(clock, STALE_AFTER, 1);
        agent = new RiskSyncAgent(
                riskMaster,
                riskEngine,
                clock,
                INTERVAL,
                new NullLogger(),
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1));
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        order = new Order();
        order.encoder.side(Side.Bid).size(1).price(100);
    }

    // The OMS thread applies what the sync thread published, as it would on its next loop pass.
    private void triggerSync() {
        agent.onStart();
        agent.doWork();
        riskEngine.applyChanges(IGNORE_KILLS);
    }

    private void advance(final long millis) {
        now[0] += millis;
        agent.doWork();
        riskEngine.applyChanges(IGNORE_KILLS);
    }

    private static final RiskEngine.KillHandler IGNORE_KILLS = new RiskEngine.KillHandler() {
        @Override
        public void onEverythingKilled() {}

        @Override
        public void onStrategyKilled(final int strategyId) {}

        @Override
        public void onListingKilled(final int listingId) {}
    };

    private static RiskPolicyRecord createRecord(
            final int policyId,
            final String type,
            final PolicyScope scope,
            final int strategyId,
            final int listingId,
            final String params,
            final boolean enabled) {
        final RiskPolicyRecord record = new RiskPolicyRecord();
        record.policyId = policyId;
        record.policyType.copy(new ViewString(type));
        record.scope = scope;
        record.strategyId = strategyId;
        record.listingId = listingId;
        record.parametersJson.copy(new ViewString(params));
        record.enabled = enabled;
        return record;
    }

    private void setupRiskMaster(final RiskPolicyRecord... records) {
        when(riskMaster.getPolicyCount()).thenReturn(records.length);
        for (int i = 0; i < records.length; i++) {
            when(riskMaster.getRecord(i)).thenReturn(records[i]);
        }
    }

    @Test
    void testRefreshAndPublishWithGlobalOrderPolicy() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();

        order.encoder.size(11);
        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));

        order.encoder.size(5);
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testRefreshAndPublishWithGlobalMarketPolicy() {
        setupRiskMaster(createRecord(1, "MAX_TOTAL_PNL_LOSS", PolicyScope.GLOBAL, 0, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishSkipsDisabledPolicies() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.GLOBAL, 0, 0, "{}", false));
        triggerSync();

        // Disabled kill switch — everything trades
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testStrategyKillSwitchBlocksOnlyThatStrategy() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.STRATEGY, STRATEGY_ID, 0, "{}", true));
        triggerSync();

        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID));
    }

    @Test
    void testListingKillSwitchBlocksOnlyThatListing() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.LISTING, 0, LISTING_ID, "{}", true));
        triggerSync();

        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1));
    }

    @Test
    void testPolicyThatCantBeBuiltKillsItsScopeAndLeavesOthersTrading() {
        setupRiskMaster(
                createRecord(1, "UNKNOWN_TYPE", PolicyScope.STRATEGY, STRATEGY_ID, 0, "{}", true),
                createRecord(2, "MAX_ORDER_SIZE", PolicyScope.LISTING, 0, LISTING_ID, "{}", true),
                createRecord(3, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID + 1), "unknown type kills its strategy");
        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID), "unreadable parameters kill their listing");
        order.encoder.size(5);
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID + 1));
        order.encoder.size(11);
        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID + 1));
    }

    @Test
    void testKillSwitchAppliesEvenWhenAnotherPolicyCantBeBuilt() {
        setupRiskMaster(
                createRecord(1, "MAX_ORDER_SIZE", PolicyScope.LISTING, 0, LISTING_ID, "{}", true),
                createRecord(2, "KILL_SWITCH", PolicyScope.GLOBAL, 0, 0, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID + 1));
    }

    @Test
    void testChangedParametersPublishAFreshPolicy() {
        final RiskPolicyRecord record =
                createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 100}", true);
        setupRiskMaster(record);
        triggerSync();

        // First sync: maxOrderSize = 100
        order.encoder.size(50);
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));

        // Second sync: update same policyId to maxOrderSize = 10
        record.parametersJson.copy(new ViewString("{\"maxOrderSize\": 10}"));
        advance(10);

        order.encoder.size(50);
        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testRefreshAndPublishWithStrategyMarketPolicy() {
        setupRiskMaster(
                createRecord(1, "MAX_TOTAL_PNL_LOSS", PolicyScope.STRATEGY, STRATEGY_ID, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID + 1, LISTING_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishWithListingMarketPolicy() {
        setupRiskMaster(
                createRecord(1, "MAX_TOTAL_PNL_LOSS", PolicyScope.LISTING, 0, LISTING_ID, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID + 1, positions, orders));
    }

    // --- load, staleness and escalation ---

    @Test
    void testPoliciesLoadImmediatelyAtStart() {
        setupRiskMaster();
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID), "nothing trades before the first load");

        triggerSync();

        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testGlobalKillSwitchAndUnreadableScopeBlockEverything() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", null, 0, 0, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 5, LISTING_ID + 5));
    }

    @Test
    void testFailedRefreshKeepsTheLastPolicies() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();
        doThrow(new RuntimeException("registry down")).when(riskMaster).refresh();

        advance(1_000);

        order.encoder.size(5);
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
        order.encoder.size(11);
        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testNoRefreshForTheStaleWindowBlocksNewOrdersUntilOneSucceeds() {
        setupRiskMaster();
        triggerSync();
        doThrow(new RuntimeException("registry down")).when(riskMaster).refresh();

        advance(29_000);
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID), "still inside the window");
        advance(2_000);
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID));
        assertFalse(riskEngine.hasKills(), "stale blocks new orders but cancels nothing");

        doNothing().when(riskMaster).refresh();
        advance(1);
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testUnchangedPoliciesPublishNothing() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();
        final RiskEngineSnapshot first = riskEngine.publishedSnapshot();

        advance(10);
        assertSame(first, riskEngine.publishedSnapshot());
    }

    @Test
    void testLatchedHaltIsRecordedAsAStrategyKillAndRetriedOnFailure() {
        setupRiskMaster();
        triggerSync();
        doThrow(new RuntimeException("registry down"))
                .doNothing()
                .when(riskMaster)
                .requestHalt(eq(7), anyString());
        riskEngine.latch(7);

        advance(1);
        advance(1);

        verify(riskMaster, times(2)).requestHalt(eq(7), anyString());
        advance(1);
        verify(riskMaster, times(2)).requestHalt(eq(7), anyString());
    }

    @Test
    void testOneHaltFailingDoesNotHoldUpAnother() {
        setupRiskMaster();
        triggerSync();
        doThrow(new RuntimeException("rejected")).when(riskMaster).requestHalt(eq(7), anyString());
        riskEngine.latch(7);
        riskEngine.latch(8);

        advance(1);

        verify(riskMaster).requestHalt(eq(8), anyString());
    }

    @Test
    void testRecordedHaltReachesTheOmsEvenIfTheOperatorAlreadyResumedIt() {
        setupRiskMaster();
        triggerSync();
        riskEngine.latch(7);

        advance(1); // records the halt and forces a refresh
        advance(1); // that refresh, with policies unchanged, still publishes the confirmation

        assertFalse(riskEngine.isLatched(7));
    }

    @Test
    void testConfirmationKeepsRidingSnapshotsTheOmsSkipped() {
        setupRiskMaster();
        triggerSync();
        riskEngine.latch(7);

        now[0] += 1;
        agent.doWork(); // records the halt
        now[0] += 1;
        agent.doWork(); // publishes the confirmation; the OMS doesn't look before the policies change
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 10}", true));
        now[0] += 1;
        agent.doWork(); // so the snapshot it does see must carry the confirmation again
        riskEngine.applyChanges(IGNORE_KILLS);

        assertFalse(riskEngine.isLatched(7));
    }
}
