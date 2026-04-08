package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;

final class RiskEngineSnapshot {

    static final int MAX_POLICIES_PER_GROUP = 64;

    final OrderPolicyGroup globalOrderGroup;
    final MarketPolicyGroup globalMarketGroup;
    final IntHashMap<OrderPolicyGroup> strategyOrderGroups;
    final IntHashMap<OrderPolicyGroup> listingOrderGroups;
    final IntHashMap<MarketPolicyGroup> strategyMarketGroups;
    final IntHashMap<MarketPolicyGroup> listingMarketGroups;

    RiskEngineSnapshot() {
        this.globalOrderGroup = new OrderPolicyGroup(MAX_POLICIES_PER_GROUP);
        this.globalMarketGroup = new MarketPolicyGroup(MAX_POLICIES_PER_GROUP);
        this.strategyOrderGroups = new IntHashMap<>();
        this.listingOrderGroups = new IntHashMap<>();
        this.strategyMarketGroups = new IntHashMap<>();
        this.listingMarketGroups = new IntHashMap<>();
    }

    OrderPolicyGroup getStrategyOrderGroup(final int strategyId) {
        return strategyOrderGroups.get(strategyId);
    }

    OrderPolicyGroup getListingOrderGroup(final int listingId) {
        return listingOrderGroups.get(listingId);
    }

    MarketPolicyGroup getStrategyMarketGroup(final int strategyId) {
        return strategyMarketGroups.get(strategyId);
    }

    MarketPolicyGroup getListingMarketGroup(final int listingId) {
        return listingMarketGroups.get(listingId);
    }
}
