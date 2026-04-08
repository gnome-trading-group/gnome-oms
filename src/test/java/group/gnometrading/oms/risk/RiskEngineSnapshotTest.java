package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals(0, snapshot.globalOrderGroup.count);
        assertEquals(0, snapshot.globalMarketGroup.count);
        assertNotNull(snapshot.globalOrderGroup);
        assertNotNull(snapshot.globalMarketGroup);
    }

    @Test
    void testMaxPoliciesPerGroupConstant() {
        assertEquals(64, RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
    }

    @Test
    void testGetStrategyOrderGroupReturnsNullWhenNotSet() {
        assertNull(snapshot.getStrategyOrderGroup(1));
    }

    @Test
    void testGetStrategyOrderGroupReturnsGroupWhenSet() {
        final OrderPolicyGroup group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        snapshot.strategyOrderGroups.put(1, group);
        assertSame(group, snapshot.getStrategyOrderGroup(1));
    }

    @Test
    void testGetListingOrderGroupReturnsNullWhenNotSet() {
        assertNull(snapshot.getListingOrderGroup(100));
    }

    @Test
    void testGetListingOrderGroupReturnsGroupWhenSet() {
        final OrderPolicyGroup group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        snapshot.listingOrderGroups.put(100, group);
        assertSame(group, snapshot.getListingOrderGroup(100));
    }

    @Test
    void testGetStrategyMarketGroupReturnsNullWhenNotSet() {
        assertNull(snapshot.getStrategyMarketGroup(1));
    }

    @Test
    void testGetStrategyMarketGroupReturnsGroupWhenSet() {
        final MarketPolicyGroup group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        snapshot.strategyMarketGroups.put(1, group);
        assertSame(group, snapshot.getStrategyMarketGroup(1));
    }

    @Test
    void testGetListingMarketGroupReturnsNullWhenNotSet() {
        assertNull(snapshot.getListingMarketGroup(100));
    }

    @Test
    void testGetListingMarketGroupReturnsGroupWhenSet() {
        final MarketPolicyGroup group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
        snapshot.listingMarketGroups.put(100, group);
        assertSame(group, snapshot.getListingMarketGroup(100));
    }
}
