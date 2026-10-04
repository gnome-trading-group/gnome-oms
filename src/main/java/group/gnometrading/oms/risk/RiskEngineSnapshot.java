package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;
import org.agrona.collections.IntHashSet;

/**
 * Risk state published by {@link RiskSyncAgent}. Never modified once published — policies are built fresh for each
 * snapshot — so the OMS thread can read it without synchronisation and a new object always means something changed.
 */
final class RiskEngineSnapshot {

    static final int MAX_POLICIES_PER_GROUP = 64;

    final OrderPolicyGroup globalOrderGroup;
    final MarketPolicyGroup globalMarketGroup;
    final IntHashMap<OrderPolicyGroup> strategyOrderGroups;
    final IntHashMap<OrderPolicyGroup> listingOrderGroups;
    final IntHashMap<MarketPolicyGroup> strategyMarketGroups;
    final IntHashMap<MarketPolicyGroup> listingMarketGroups;

    /** Increases with each published snapshot, so the sync thread can tell which ones the OMS has applied. */
    long sequence;

    boolean globalKill;
    final IntHashSet killedStrategies = new IntHashSet();
    final IntHashSet killedListings = new IntHashSet();

    /**
     * Strategies whose latched halts the registry has accepted since the previous snapshot. Their latches can be
     * released: the registry now holds the halt, and if an operator already resumed it, the halt is over.
     */
    final IntHashSet confirmedHalts = new IntHashSet();

    RiskEngineSnapshot() {
        this.globalOrderGroup = new OrderPolicyGroup(MAX_POLICIES_PER_GROUP);
        this.globalMarketGroup = new MarketPolicyGroup(MAX_POLICIES_PER_GROUP);
        this.strategyOrderGroups = new IntHashMap<>();
        this.listingOrderGroups = new IntHashMap<>();
        this.strategyMarketGroups = new IntHashMap<>();
        this.listingMarketGroups = new IntHashMap<>();
    }

    boolean hasKills() {
        return globalKill || !killedStrategies.isEmpty() || !killedListings.isEmpty();
    }

    boolean blocks(final int strategyId, final int listingId) {
        return globalKill || killedStrategies.contains(strategyId) || killedListings.contains(listingId);
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
