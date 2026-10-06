package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.LongHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.LongHashSet;

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
    /** Policies for one strategy on one listing, keyed by {@link #pairKey}. */
    final LongHashMap<OrderPolicyGroup> strategyListingOrderGroups;

    final LongHashMap<MarketPolicyGroup> strategyListingMarketGroups;

    /** Increases with each published snapshot, so the sync thread can tell which ones the OMS has applied. */
    long sequence;

    boolean globalKill;
    final IntHashSet killedStrategies = new IntHashSet();
    final IntHashSet killedListings = new IntHashSet();
    /** One strategy killed on one listing only, keyed by {@link #pairKey}. */
    final LongHashSet killedStrategyListings = new LongHashSet();

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
        this.strategyListingOrderGroups = new LongHashMap<>();
        this.strategyListingMarketGroups = new LongHashMap<>();
    }

    /**
     * Adds a policy to the group its target selects: strategy 0 and listing 0 is global, listing 0 alone is one
     * strategy across listings, strategy 0 alone is one listing across strategies, and both set is one strategy on
     * one listing.
     */
    void addPolicy(final int strategyId, final int listingId, final Configurable policy, final int policyId) {
        if (policy instanceof OrderRiskPolicy orderPolicy) {
            addOrderPolicy(strategyId, listingId, orderPolicy, policyId);
        } else {
            addMarketPolicy(strategyId, listingId, (MarketRiskPolicy) policy, policyId);
        }
    }

    // A kill whose target can't be read stops everything rather than nothing.
    void addKill(final int strategyId, final int listingId) {
        if (strategyId < 0 || listingId < 0 || (strategyId == 0 && listingId == 0)) {
            globalKill = true;
        } else if (listingId == 0) {
            killedStrategies.add(strategyId);
        } else if (strategyId == 0) {
            killedListings.add(listingId);
        } else {
            killedStrategyListings.add(pairKey(strategyId, listingId));
        }
    }

    private void addOrderPolicy(
            final int strategyId, final int listingId, final OrderRiskPolicy policy, final int policyId) {
        final OrderPolicyGroup group;
        if (strategyId == 0 && listingId == 0) {
            group = globalOrderGroup;
        } else if (listingId == 0) {
            group = orderGroup(strategyOrderGroups, strategyId);
        } else if (strategyId == 0) {
            group = orderGroup(listingOrderGroups, listingId);
        } else {
            group = orderGroup(strategyListingOrderGroups, pairKey(strategyId, listingId));
        }
        group.add(policy, policyId);
    }

    private void addMarketPolicy(
            final int strategyId, final int listingId, final MarketRiskPolicy policy, final int policyId) {
        final MarketPolicyGroup group;
        if (strategyId == 0 && listingId == 0) {
            group = globalMarketGroup;
        } else if (listingId == 0) {
            group = marketGroup(strategyMarketGroups, strategyId);
        } else if (strategyId == 0) {
            group = marketGroup(listingMarketGroups, listingId);
        } else {
            group = marketGroup(strategyListingMarketGroups, pairKey(strategyId, listingId));
        }
        group.add(policy, policyId);
    }

    private static OrderPolicyGroup orderGroup(final IntHashMap<OrderPolicyGroup> groups, final int key) {
        OrderPolicyGroup group = groups.get(key);
        if (group == null) {
            group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
            groups.put(key, group);
        }
        return group;
    }

    private static OrderPolicyGroup orderGroup(final LongHashMap<OrderPolicyGroup> groups, final long key) {
        OrderPolicyGroup group = groups.get(key);
        if (group == null) {
            group = new OrderPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
            groups.put(key, group);
        }
        return group;
    }

    private static MarketPolicyGroup marketGroup(final IntHashMap<MarketPolicyGroup> groups, final int key) {
        MarketPolicyGroup group = groups.get(key);
        if (group == null) {
            group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
            groups.put(key, group);
        }
        return group;
    }

    private static MarketPolicyGroup marketGroup(final LongHashMap<MarketPolicyGroup> groups, final long key) {
        MarketPolicyGroup group = groups.get(key);
        if (group == null) {
            group = new MarketPolicyGroup(RiskEngineSnapshot.MAX_POLICIES_PER_GROUP);
            groups.put(key, group);
        }
        return group;
    }

    static long pairKey(final int strategyId, final int listingId) {
        return ((long) strategyId << Integer.SIZE) | Integer.toUnsignedLong(listingId);
    }

    static int pairStrategy(final long key) {
        return (int) (key >>> Integer.SIZE);
    }

    static int pairListing(final long key) {
        return (int) key;
    }

    boolean hasKills() {
        return globalKill
                || !killedStrategies.isEmpty()
                || !killedListings.isEmpty()
                || !killedStrategyListings.isEmpty();
    }

    boolean blocks(final int strategyId, final int listingId) {
        return globalKill
                || killedStrategies.contains(strategyId)
                || killedListings.contains(listingId)
                || (!killedStrategyListings.isEmpty()
                        && killedStrategyListings.contains(pairKey(strategyId, listingId)));
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

    OrderPolicyGroup getStrategyListingOrderGroup(final int strategyId, final int listingId) {
        return strategyListingOrderGroups.isEmpty()
                ? null
                : strategyListingOrderGroups.get(pairKey(strategyId, listingId));
    }

    MarketPolicyGroup getStrategyListingMarketGroup(final int strategyId, final int listingId) {
        return strategyListingMarketGroups.isEmpty()
                ? null
                : strategyListingMarketGroups.get(pairKey(strategyId, listingId));
    }
}
