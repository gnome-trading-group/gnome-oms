package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.oms.RecordingLogger;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.risk.RiskPolicyRecord;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.Side;
import group.gnometrading.strings.ViewString;
import java.time.Duration;
import java.util.List;
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
    private static final int EXCHANGE_ID = 3;
    private static final int OTHER_EXCHANGE_ID = 4;
    private static final Duration INTERVAL = Duration.ofMillis(1);
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);

    @Mock
    private RiskMaster riskMaster;

    private final long[] now = {0};
    private final EpochClock clock = () -> now[0];

    @Mock
    private OrderStateManager orders;

    private final RecordingLogger logger = new RecordingLogger();
    private RiskEngine riskEngine;
    private RiskSyncAgent agent;
    private DefaultPositionTracker positions;
    private Order order;

    @BeforeEach
    void setUp() {
        useAgent(ListingDirectory.EMPTY, false);
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        order = new Order();
        order.encoder.side(Side.Bid).size(1).price(100);
    }

    private void useAgent(final ListingDirectory directory, final boolean live) {
        riskEngine = RiskEngine.syncedFromRegistry(clock, STALE_AFTER, directory, 1);
        agent = new RiskSyncAgent(
                riskMaster,
                riskEngine,
                clock,
                INTERVAL,
                logger,
                new SharedPriceBuffer(1),
                new PriceSlotRegistry(1),
                STRATEGY_ID,
                live);
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
        public void onStrategyListingKilled(final int strategyId, final int listingId) {}

        @Override
        public void onListingKilled(final int listingId) {}

        @Override
        public void onExchangeKilled(final int exchangeId) {}

        @Override
        public void onStrategyExchangeKilled(final int strategyId, final int exchangeId) {}
    };

    private static RiskPolicyRecord createRecord(
            final int policyId,
            final String type,
            final int strategyId,
            final int listingId,
            final String params,
            final boolean enabled) {
        final RiskPolicyRecord record = new RiskPolicyRecord();
        record.policyId = policyId;
        record.policyType.copy(new ViewString(type));
        record.strategyId = strategyId;
        record.listingId = listingId;
        record.parametersJson.copy(new ViewString(params));
        record.enabled = enabled;
        return record;
    }

    private static RiskPolicyRecord onExchange(final int exchangeId, final RiskPolicyRecord record) {
        record.exchangeId = exchangeId;
        return record;
    }

    /** One row of each policy a live session must have, for every strategy and listing. */
    private static RiskPolicyRecord[] requiredEverywhere() {
        return new RiskPolicyRecord[] {
            createRecord(91, "MAX_COMMITTED_CAPITAL", 0, 0, "{\"maxCapital\": 1000}", true),
            createRecord(92, "MAX_TOTAL_PNL_LOSS", 0, 0, "{\"maxLoss\": 1000}", true),
            createRecord(93, "MAX_POSITION", 0, 0, "{\"maxPosition\": 1000}", true),
            createRecord(94, "MAX_CONSECUTIVE_REJECTS", 0, 0, "{\"maxRejects\": 5}", true)
        };
    }

    private static RiskPolicyRecord forSession(final String sessionId, final RiskPolicyRecord record) {
        record.sessionId.copy(new ViewString(sessionId));
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
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();

        order.encoder.size(11);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));

        order.encoder.size(5);
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testAListingScopedOpenOrderLimitCountsThatListingAndAStrategyOneCountsThemAll() {
        setupRiskMaster(
                createRecord(1, "MAX_OPEN_ORDERS", 0, LISTING_ID, "{\"maxOpenOrders\": 1}", true),
                createRecord(2, "MAX_OPEN_ORDERS", STRATEGY_ID, 0, "{\"maxOpenOrders\": 2}", true));
        triggerSync();

        positions.addOpenOrder(STRATEGY_ID, LISTING_ID + 1);
        assertNull(
                riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "1 open elsewhere");

        positions.addOpenOrder(STRATEGY_ID, LISTING_ID);
        assertNotNull(
                riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "listing limit of 1");
        assertNotNull(
                riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID),
                "strategy limit of 2");
    }

    @Test
    void testAListingScopedLossLimitIgnoresTheStrategysOtherListingsAndAStrategyScopedOneSumsThem() {
        setupRiskMaster(
                createRecord(1, "MAX_TOTAL_PNL_LOSS", 0, LISTING_ID, "{\"maxLoss\": 50}", true),
                createRecord(2, "MAX_TOTAL_PNL_LOSS", STRATEGY_ID + 1, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID + 1, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID + 1).realizedPnl = -51L;
        assertFalse(
                riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders),
                "loss elsewhere");

        positions.applyStrategyFill(STRATEGY_ID + 1, LISTING_ID + 1, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID + 1, LISTING_ID + 1).realizedPnl = -51L;
        positions.applyStrategyFill(STRATEGY_ID + 1, LISTING_ID + 2, Side.Bid, 1, 100, 0);
        assertTrue(
                riskEngine.checkMarketPolicies(STRATEGY_ID + 1, LISTING_ID + 2, EXCHANGE_ID, positions, orders),
                "summed");
    }

    @Test
    void testRefreshAndPublishWithGlobalMarketPolicy() {
        setupRiskMaster(createRecord(1, "MAX_TOTAL_PNL_LOSS", 0, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishSkipsDisabledPolicies() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", 0, 0, "{}", false));
        triggerSync();

        // Disabled kill switch — everything trades
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testStrategyKillSwitchBlocksOnlyThatStrategy() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", STRATEGY_ID, 0, "{}", true));
        triggerSync();

        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testListingKillSwitchBlocksOnlyThatListing() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", 0, LISTING_ID, "{}", true));
        triggerSync();

        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID));
    }

    @Test
    void testPolicyThatCantBeBuiltKillsItsScopeAndLeavesOthersTrading() {
        setupRiskMaster(
                createRecord(1, "UNKNOWN_TYPE", STRATEGY_ID, 0, "{}", true),
                createRecord(2, "MAX_ORDER_SIZE", 0, LISTING_ID, "{}", true),
                createRecord(3, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID), "unknown type kills its strategy");
        assertTrue(
                riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID),
                "unreadable parameters kill their listing");
        order.encoder.size(5);
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID + 1, EXCHANGE_ID));
        order.encoder.size(11);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID + 1, EXCHANGE_ID));
    }

    @Test
    void testKillSwitchAppliesEvenWhenAnotherPolicyCantBeBuilt() {
        setupRiskMaster(
                createRecord(1, "MAX_ORDER_SIZE", 0, LISTING_ID, "{}", true),
                createRecord(2, "KILL_SWITCH", 0, 0, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID + 1, EXCHANGE_ID));
    }

    @Test
    void testChangedParametersPublishAFreshPolicy() {
        final RiskPolicyRecord record = createRecord(1, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 100}", true);
        setupRiskMaster(record);
        triggerSync();

        // First sync: maxOrderSize = 100
        order.encoder.size(50);
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));

        // Second sync: update same policyId to maxOrderSize = 10
        record.parametersJson.copy(new ViewString("{\"maxOrderSize\": 10}"));
        advance(10);

        order.encoder.size(50);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testRefreshAndPublishWithStrategyMarketPolicy() {
        setupRiskMaster(createRecord(1, "MAX_TOTAL_PNL_LOSS", STRATEGY_ID, 0, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testRefreshAndPublishWithListingMarketPolicy() {
        setupRiskMaster(createRecord(1, "MAX_TOTAL_PNL_LOSS", 0, LISTING_ID, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;

        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID, positions, orders));
    }

    // --- load, staleness and escalation ---

    @Test
    void testPoliciesLoadImmediatelyAtStart() {
        setupRiskMaster();
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "nothing trades before the first load");

        triggerSync();

        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testAStrategyRowIsNotGlobalAndAListingRowCoversEveryStrategyOnIt() {
        setupRiskMaster(
                createRecord(1, "KILL_SWITCH", STRATEGY_ID, 0, "{}", true),
                createRecord(2, "KILL_SWITCH", 0, LISTING_ID + 1, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID + 7, EXCHANGE_ID));
        assertFalse(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID), "another strategy");
        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID + 1, EXCHANGE_ID));
        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 2, LISTING_ID + 1, EXCHANGE_ID));
    }

    @Test
    void testAStrategyAndListingKillStopsOnlyThatStrategyOnThatListing() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", STRATEGY_ID, LISTING_ID, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID), "its other listings");
        assertFalse(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID), "other strategies on the listing");
    }

    @Test
    void testAStrategyAndListingLimitJudgesOnlyThatPair() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", STRATEGY_ID, LISTING_ID, "{\"maxOrderSize\": 10}", true));
        triggerSync();

        order.encoder.size(11);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1, EXCHANGE_ID));
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testAStrategyAndListingLossLimitJudgesOnlyThatPair() {
        setupRiskMaster(createRecord(1, "MAX_TOTAL_PNL_LOSS", STRATEGY_ID, LISTING_ID, "{\"maxLoss\": 50}", true));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID + 1, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID + 1).realizedPnl = -500L;
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        assertFalse(
                riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders),
                "loss elsewhere");

        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;
        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testOwnSessionRowsApplyAndOtherSessionsRowsAreSkipped() {
        when(riskMaster.sessionId()).thenReturn("mine");
        setupRiskMaster(
                forSession("mine", createRecord(1, "MAX_ORDER_SIZE", STRATEGY_ID, 0, "{\"maxOrderSize\": 10}", true)),
                forSession("theirs", createRecord(2, "KILL_SWITCH", STRATEGY_ID, 0, "{}", true)));
        triggerSync();

        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "another session's kill");
        order.encoder.size(11);
        assertNotNull(
                riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID),
                "this session's limit");
    }

    @Test
    void testSessionRowsAreSkippedOutsideASession() {
        setupRiskMaster(forSession("any", createRecord(1, "KILL_SWITCH", STRATEGY_ID, 0, "{}", true)));
        triggerSync();

        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testAKillWhoseTargetCantBeReadBlocksEverything() {
        setupRiskMaster(createRecord(1, "KILL_SWITCH", -1, 0, "{}", true));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 5, LISTING_ID + 5, EXCHANGE_ID));
    }

    @Test
    void testFailedRefreshKeepsTheLastPolicies() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 10}", true));
        triggerSync();
        doThrow(new RuntimeException("registry down")).when(riskMaster).refresh();

        advance(1_000);

        order.encoder.size(5);
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        order.encoder.size(11);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testNoRefreshForTheStaleWindowBlocksNewOrdersUntilOneSucceeds() {
        setupRiskMaster();
        triggerSync();
        doThrow(new RuntimeException("registry down")).when(riskMaster).refresh();

        advance(29_000);
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "still inside the window");
        advance(2_000);
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertFalse(riskEngine.hasKills(), "stale blocks new orders but cancels nothing");

        doNothing().when(riskMaster).refresh();
        advance(1);
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testUnchangedPoliciesPublishNothing() {
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 10}", true));
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
                .requestHalt(eq(7), eq(0), eq(0), anyString());
        riskEngine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        advance(1);
        advance(1);

        verify(riskMaster, times(2)).requestHalt(eq(7), eq(0), eq(0), anyString());
        advance(1);
        verify(riskMaster, times(2)).requestHalt(eq(7), eq(0), eq(0), anyString());
    }

    @Test
    void testOneHaltFailingDoesNotHoldUpAnother() {
        setupRiskMaster();
        triggerSync();
        doThrow(new RuntimeException("rejected")).when(riskMaster).requestHalt(eq(7), eq(0), eq(0), anyString());
        riskEngine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);
        riskEngine.latch(8, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        advance(1);

        verify(riskMaster).requestHalt(eq(8), eq(0), eq(0), anyString());
    }

    @Test
    void testRecordedHaltReachesTheOmsEvenIfTheOperatorAlreadyResumedIt() {
        setupRiskMaster();
        triggerSync();
        riskEngine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        advance(1); // records the halt and forces a refresh
        advance(1); // that refresh, with policies unchanged, still publishes the confirmation

        assertFalse(riskEngine.isLatchedAt(7, 0, 0));
    }

    @Test
    void testConfirmationKeepsRidingSnapshotsTheOmsSkipped() {
        setupRiskMaster();
        triggerSync();
        riskEngine.latch(7, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        now[0] += 1;
        agent.doWork(); // records the halt
        now[0] += 1;
        agent.doWork(); // publishes the confirmation; the OMS doesn't look before the policies change
        setupRiskMaster(createRecord(1, "MAX_ORDER_SIZE", 0, 0, "{\"maxOrderSize\": 10}", true));
        now[0] += 1;
        agent.doWork(); // so the snapshot it does see must carry the confirmation again
        riskEngine.applyChanges(IGNORE_KILLS);

        assertFalse(riskEngine.isLatchedAt(7, 0, 0));
    }

    // --- exchange scope ---

    @Test
    void testExchangeRowsKillAndLimitOnlyThatExchange() {
        setupRiskMaster(
                onExchange(EXCHANGE_ID, createRecord(1, "KILL_SWITCH", 0, 0, "{}", true)),
                onExchange(
                        OTHER_EXCHANGE_ID,
                        createRecord(2, "MAX_ORDER_SIZE", STRATEGY_ID, 0, "{\"maxOrderSize\": 10}", true)));
        triggerSync();

        assertTrue(riskEngine.isBlocked(STRATEGY_ID + 1, LISTING_ID, EXCHANGE_ID));
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID + 1, OTHER_EXCHANGE_ID));
        order.encoder.size(11);
        assertNotNull(riskEngine.check(order, positions, orders, STRATEGY_ID, LISTING_ID + 1, OTHER_EXCHANGE_ID));
        assertNull(riskEngine.check(order, positions, orders, STRATEGY_ID + 1, LISTING_ID + 1, OTHER_EXCHANGE_ID));
    }

    @Test
    void testAnExchangeLossLimitSumsOnlyThatExchangesListings() {
        useAgent(
                TestListings.directory(
                        TestListings.binary(LISTING_ID, EXCHANGE_ID),
                        TestListings.binary(LISTING_ID + 1, OTHER_EXCHANGE_ID)),
                false);
        setupRiskMaster(
                onExchange(EXCHANGE_ID, createRecord(1, "MAX_TOTAL_PNL_LOSS", 0, 0, "{\"maxLoss\": 50}", true)));
        triggerSync();

        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID + 1, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID + 1).realizedPnl = -500L;
        positions.applyStrategyFill(STRATEGY_ID, LISTING_ID, Side.Bid, 1, 100, 0);
        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -40L;
        assertFalse(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));

        positions.getStrategyPosition(STRATEGY_ID, LISTING_ID).realizedPnl = -51L;
        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
    }

    @Test
    void testChangingOnlyTheExchangePublishesAFreshSnapshot() {
        final RiskPolicyRecord record = onExchange(EXCHANGE_ID, createRecord(1, "KILL_SWITCH", 0, 0, "{}", true));
        setupRiskMaster(record);
        triggerSync();
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));

        record.exchangeId = OTHER_EXCHANGE_ID;
        advance(10);
        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertTrue(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, OTHER_EXCHANGE_ID));
    }

    // --- fail-closed ---

    @Test
    void testALiveSessionCoveredOnEveryListingTrades() {
        useAgent(twoVenues(), true);
        setupRiskMaster(requiredEverywhere());
        triggerSync();

        assertFalse(riskEngine.isBlocked(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
        assertTrue(logger.linesFor(LogMessage.RISK_UNGUARDED).isEmpty());
    }

    @Test
    void testALiveSessionMissingARequiredPolicyStopsEverythingAndSaysWhatIsMissingOnce() {
        useAgent(twoVenues(), true);
        final RiskPolicyRecord[] required = requiredEverywhere();
        setupRiskMaster(
                required[0],
                required[1],
                required[2],
                onExchange(EXCHANGE_ID, createRecord(4, "MAX_CONSECUTIVE_REJECTS", 0, 0, "{\"maxRejects\": 5}", true)));
        triggerSync();

        assertTrue(riskEngine.isHalted(STRATEGY_ID, LISTING_ID, EXCHANGE_ID), "even where it is covered");
        assertTrue(riskEngine.hasKills());
        assertEquals(
                List.of("RISK_UNGUARDED " + STRATEGY_ID + " " + (LISTING_ID + 1) + " 3"),
                logger.linesFor(LogMessage.RISK_UNGUARDED));

        riskEngine.latch(9, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);
        advance(1);
        advance(1);
        assertEquals(1, logger.linesFor(LogMessage.RISK_UNGUARDED).size(), "rebuilt for a halt, not a change");
    }

    @Test
    void testAPaperSessionNeedsNoPolicies() {
        useAgent(twoVenues(), false);
        setupRiskMaster();
        triggerSync();

        assertFalse(riskEngine.isHalted(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testOnlyRowsThatBuildAndCoverTheListingCount() {
        useAgent(twoVenues(), true);
        final RiskPolicyRecord[] required = requiredEverywhere();
        setupRiskMaster(
                createRecord(1, "MAX_COMMITTED_CAPITAL", STRATEGY_ID + 1, 0, "{\"maxCapital\": 1}", true),
                createRecord(2, "MAX_COMMITTED_CAPITAL", 0, LISTING_ID, "{\"maxCapital\": 1}", true),
                onExchange(OTHER_EXCHANGE_ID, createRecord(3, "MAX_COMMITTED_CAPITAL", 0, 0, "{}", true)),
                createRecord(4, "MAX_COMMITTED_CAPITAL", 0, 0, "{\"maxCapital\": 1}", false),
                required[1],
                required[2],
                required[3]);
        triggerSync();

        assertEquals(
                List.of("RISK_UNGUARDED " + STRATEGY_ID + " " + (LISTING_ID + 1) + " 0"),
                logger.linesFor(LogMessage.RISK_UNGUARDED),
                "another strategy's, another listing's, an unreadable and a disabled row all leave a gap");
    }

    @Test
    void testRowsPerListingAndPerExchangeCoverTogether() {
        useAgent(twoVenues(), true);
        final RiskPolicyRecord[] required = requiredEverywhere();
        setupRiskMaster(
                createRecord(1, "MAX_COMMITTED_CAPITAL", STRATEGY_ID, LISTING_ID, "{\"maxCapital\": 1}", true),
                onExchange(
                        OTHER_EXCHANGE_ID, createRecord(2, "MAX_COMMITTED_CAPITAL", 0, 0, "{\"maxCapital\": 1}", true)),
                required[1],
                required[2],
                required[3]);
        triggerSync();

        assertFalse(riskEngine.isHalted(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    @Test
    void testAddingTheMissingPolicyLiftsTheStop() {
        useAgent(twoVenues(), true);
        final RiskPolicyRecord[] required = requiredEverywhere();
        setupRiskMaster(required[0], required[1], required[2]);
        triggerSync();
        assertTrue(riskEngine.isHalted(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));

        setupRiskMaster(required);
        advance(10);
        assertFalse(riskEngine.isHalted(STRATEGY_ID, LISTING_ID, EXCHANGE_ID));
    }

    private static ListingDirectory twoVenues() {
        return TestListings.directory(
                TestListings.binary(LISTING_ID, EXCHANGE_ID), TestListings.binary(LISTING_ID + 1, OTHER_EXCHANGE_ID));
    }

    // --- scoped halts ---

    @Test
    void testARejectStreakHaltIsRecordedOnItsPolicysScopeWithItsLength() {
        setupRiskMaster(onExchange(
                EXCHANGE_ID, createRecord(31, "MAX_CONSECUTIVE_REJECTS", 0, 0, "{\"maxRejects\": 2}", true)));
        triggerSync();
        riskEngine.rejectStreaks().recordReject(STRATEGY_ID, EXCHANGE_ID, LISTING_ID);
        riskEngine.rejectStreaks().recordReject(STRATEGY_ID, EXCHANGE_ID, LISTING_ID);
        assertTrue(riskEngine.checkMarketPolicies(STRATEGY_ID, LISTING_ID, EXCHANGE_ID, positions, orders));
        assertTrue(riskEngine.latchBreach(STRATEGY_ID, 0));

        advance(1);
        verify(riskMaster).requestHalt(STRATEGY_ID, EXCHANGE_ID, 0, "2 consecutive venue rejects (policy 31)");
        advance(1);
        assertFalse(riskEngine.isLatchedAt(STRATEGY_ID, EXCHANGE_ID, 0), "the registry holds it now");
    }

    @Test
    void testEachHaltCauseNamesItself() {
        setupRiskMaster();
        triggerSync();
        riskEngine.latch(7, 0, LISTING_ID, RiskEngine.CAUSE_MARKET_LIMIT, 12);
        riskEngine.latch(8, 0, 0, RiskEngine.CAUSE_LEDGER, 0);
        riskEngine.latch(9, 0, 0, RiskEngine.CAUSE_MARKET_LIMIT, 0);

        advance(1);

        verify(riskMaster).requestHalt(7, 0, LISTING_ID, "market risk limit breached (policy 12)");
        verify(riskMaster).requestHalt(8, 0, 0, "ledger failing");
        verify(riskMaster).requestHalt(9, 0, 0, "market risk limit breached");
        advance(1);
        assertFalse(riskEngine.isLatchedAt(7, 0, LISTING_ID));
        assertFalse(riskEngine.isLatchedAt(8, 0, 0));
    }
}
