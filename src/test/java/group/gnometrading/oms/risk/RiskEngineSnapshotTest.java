package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.risk.RiskMaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskEngineSnapshotTest {

    private RiskEngineSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new RiskEngineSnapshot();
    }

    @Test
    void testInitialStateHasEmptyGroups() {
        assertNotNull(snapshot.orderGroups.global);
        assertNotNull(snapshot.marketGroups.global);
        assertEquals(0, snapshot.orderGroups.global.count);
        assertEquals(0, snapshot.marketGroups.global.count);
        assertFalse(snapshot.orderGroups.hasScoped());
        assertFalse(snapshot.hasMarketPolicies());
    }

    @Test
    void testAGroupHoldsEveryPolicyTheRegistryCanSend() {
        assertEquals(RiskMaster.MAX_POLICIES, RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        for (int i = 0; i < RiskMaster.MAX_POLICIES; i++) {
            snapshot.addPolicy(0, 0, 0, new MaxOrderSizePolicy(1), i);
        }
        assertEquals(RiskMaster.MAX_POLICIES, snapshot.orderGroups.global.count);
    }

    @Test
    void testEachTargetSelectsItsOwnGroup() {
        final MaxOrderSizePolicy global = new MaxOrderSizePolicy(1);
        final MaxOrderSizePolicy exchange = new MaxOrderSizePolicy(2);
        final MaxOrderSizePolicy strategy = new MaxOrderSizePolicy(3);
        final MaxOrderSizePolicy strategyExchange = new MaxOrderSizePolicy(4);
        final MaxOrderSizePolicy strategyListing = new MaxOrderSizePolicy(5);
        final MaxOrderSizePolicy listing = new MaxOrderSizePolicy(6);
        snapshot.addPolicy(0, 0, 0, global, 1);
        snapshot.addPolicy(0, 0, 9, exchange, 2);
        snapshot.addPolicy(7, 0, 0, strategy, 3);
        snapshot.addPolicy(7, 0, 9, strategyExchange, 4);
        snapshot.addPolicy(7, 100, 0, strategyListing, 5);
        snapshot.addPolicy(0, 100, 0, listing, 6);

        final ScopedGroups<OrderPolicyGroup> groups = snapshot.orderGroups;
        assertSame(global, groups.global.policies[0]);
        assertSame(exchange, groups.exchange(9).policies[0]);
        assertSame(strategy, groups.strategy(7).policies[0]);
        assertSame(strategyExchange, groups.strategyExchange(7, 9).policies[0]);
        assertSame(strategyListing, groups.strategyListing(7, 100).policies[0]);
        assertSame(listing, groups.listing(100).policies[0]);
        assertNull(groups.exchange(8));
        assertNull(groups.strategyExchange(8, 9));
    }

    @Test
    void testAListingBeatsAnExchange() {
        snapshot.addPolicy(7, 100, 9, new MaxOrderSizePolicy(1), 1);
        assertNotNull(snapshot.orderGroups.strategyListing(7, 100));
        assertNull(snapshot.orderGroups.strategyExchange(7, 9));

        snapshot.addKill(0, 100, 9);
        assertTrue(snapshot.killedListings.contains(100));
        assertFalse(snapshot.killedExchanges.contains(9));
    }

    @Test
    void testMarketPoliciesGoToTheMarketGroups() {
        snapshot.addPolicy(0, 0, 9, new MaxConsecutiveRejectsPolicy(new RejectStreaks(), 0, 9, 3), 1);
        assertEquals(1, snapshot.marketGroups.exchange(9).count);
        assertNull(snapshot.orderGroups.exchange(9));
        assertTrue(snapshot.hasMarketPolicies());
    }

    @Test
    void testKillsBlockOnlyTheirScope() {
        snapshot.addKill(0, 0, 9);
        snapshot.addKill(7, 0, 8);
        assertTrue(snapshot.blocks(1, 100, 9), "the exchange kill covers every strategy there");
        assertTrue(snapshot.blocks(7, 100, 8));
        assertFalse(snapshot.blocks(6, 100, 8), "the strategy+exchange kill covers only that strategy");
        assertFalse(snapshot.blocks(7, 100, 7));
        assertTrue(snapshot.killsExactly(7, 8, 0));
        assertFalse(snapshot.killsExactly(7, 0, 0));
    }

    @Test
    void testAKillWithAnUnreadableTargetStopsEverything() {
        snapshot.addKill(1, 0, -1);
        assertTrue(snapshot.globalKill);
    }

    @Test
    void testUnguardedStopsEverything() {
        snapshot.unguarded = true;
        assertTrue(snapshot.stopsEverything());
        assertTrue(snapshot.hasKills());
        assertTrue(snapshot.blocks(1, 2, 3));
    }
}
