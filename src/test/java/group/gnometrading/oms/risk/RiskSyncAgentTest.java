package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import group.gnometrading.oms.position.DefaultPositionTracker;
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

    @Mock
    private RiskMaster riskMaster;

    @Mock
    private EpochClock clock;

    @Mock
    private OrderStateManager orders;

    private RiskEngine riskEngine;
    private RiskSyncAgent agent;
    private DefaultPositionTracker positions;
    private Order order;

    @BeforeEach
    void setUp() {
        riskEngine = new RiskEngine();
        agent = new RiskSyncAgent(riskMaster, riskEngine, clock, INTERVAL);
        positions = new DefaultPositionTracker();
        order = new Order();
        order.encoder.side(Side.Bid).size(1).price(100);

        // clock returns 0 on start, then 10 on doWork() — fires the schedule
        when(clock.time()).thenReturn(0L, 10L);
    }

    private void triggerSync() {
        agent.onStart();
        agent.doWork();
    }

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
        setupRiskMaster(createRecord(1, "MAX_PNL_LOSS", PolicyScope.GLOBAL, 0, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51.0;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishSkipsDisabledPolicies() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.GLOBAL, 0, 0, "{}", false));
        triggerSync();

        // KillSwitch disabled — engine should pass all orders
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testRefreshAndPublishWithStrategyOrderPolicy() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.STRATEGY, STRATEGY_ID, 0, "{}", true));
        triggerSync();

        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID));
    }

    @Test
    void testRefreshAndPublishWithListingOrderPolicy() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", PolicyScope.LISTING, 0, LISTING_ID, "{}", true));
        triggerSync();

        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1));
    }

    @Test
    void testRefreshAndPublishThrowsOnUnknownPolicyType() {
        setupRiskMaster(createRecord(1, "UNKNOWN_TYPE", PolicyScope.GLOBAL, 0, 0, "{}", true));

        assertThrows(IllegalStateException.class, this::triggerSync);
    }

    @Test
    void testPolicyCacheReusesExistingPolicyWithUpdatedParameters() {
        final RiskPolicyRecord record =
                createRecord(1, "MAX_ORDER_SIZE", PolicyScope.GLOBAL, 0, 0, "{\"maxOrderSize\": 100}", true);
        setupRiskMaster(record);
        triggerSync();

        // First sync: maxOrderSize = 100
        order.encoder.size(50);
        assertTrue(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));

        // Second sync: update same policyId to maxOrderSize = 10
        record.parametersJson.copy(new ViewString("{\"maxOrderSize\": 10}"));
        when(clock.time()).thenReturn(20L, 30L);
        agent.onStart();
        agent.doWork();

        order.encoder.size(50);
        assertFalse(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID));
    }

    @Test
    void testRefreshAndPublishWithStrategyMarketPolicy() {
        setupRiskMaster(
                createRecord(1, "MAX_PNL_LOSS", PolicyScope.STRATEGY, STRATEGY_ID, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51.0;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID + 1, LISTING_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishWithListingMarketPolicy() {
        setupRiskMaster(createRecord(1, "MAX_PNL_LOSS", PolicyScope.LISTING, 0, LISTING_ID, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51.0;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID + 1, positions, orders));
    }
}
