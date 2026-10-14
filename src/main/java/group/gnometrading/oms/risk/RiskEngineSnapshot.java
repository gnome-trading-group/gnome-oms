package group.gnometrading.oms.risk;

import group.gnometrading.risk.RiskMaster;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.LongHashSet;

/**
 * Risk state published by {@link RiskSyncAgent}. Never modified once published — policies are built fresh for each
 * snapshot — so the OMS thread can read it without synchronisation and a new object always means something changed.
 */
final class RiskEngineSnapshot {

    // Every policy the registry sends could target the same group.
    static final int MAX_POLICIES_PER_GROUP = RiskMaster.MAX_POLICIES;

    final ScopedGroups<OrderPolicyGroup> orderGroups =
            new ScopedGroups<>(() -> new OrderPolicyGroup(MAX_POLICIES_PER_GROUP));
    final ScopedGroups<MarketPolicyGroup> marketGroups =
            new ScopedGroups<>(() -> new MarketPolicyGroup(MAX_POLICIES_PER_GROUP));

    /** Increases with each published snapshot, so the sync thread can tell which ones the OMS has applied. */
    long sequence;

    boolean globalKill;
    /**
     * A live session lacks a required policy on some listing, so it trades on limits nobody set. Treated as a global
     * kill until the policies are added.
     */
    boolean unguarded;

    final IntHashSet killedStrategies = new IntHashSet();
    final IntHashSet killedExchanges = new IntHashSet();
    final IntHashSet killedListings = new IntHashSet();
    /** One strategy killed on one exchange only, keyed by {@link #pairKey}. */
    final LongHashSet killedStrategyExchanges = new LongHashSet();
    /** One strategy killed on one listing only, keyed by {@link #pairKey}. */
    final LongHashSet killedStrategyListings = new LongHashSet();

    /**
     * Latched halts the registry has accepted since the previous snapshot, by the scope they were latched at. Their
     * latches can be released: the registry now holds the halt, and if an operator already resumed it, it is over.
     */
    final IntHashSet confirmedStrategyHalts = new IntHashSet();

    final LongHashSet confirmedStrategyExchangeHalts = new LongHashSet();
    final LongHashSet confirmedStrategyListingHalts = new LongHashSet();

    /** Adds a policy to the group its target selects; 0 is every strategy, listing or exchange. */
    void addPolicy(
            final int strategyId,
            final int listingId,
            final int exchangeId,
            final Configurable policy,
            final int policyId) {
        if (policy instanceof OrderRiskPolicy orderPolicy) {
            orderGroups.groupFor(strategyId, listingId, exchangeId).add(orderPolicy, policyId);
        } else {
            marketGroups.groupFor(strategyId, listingId, exchangeId).add((MarketRiskPolicy) policy, policyId);
        }
    }

    /** A listing beats an exchange, which beats no scope. A kill whose target can't be read stops everything. */
    void addKill(final int strategyId, final int listingId, final int exchangeId) {
        if (strategyId < 0 || listingId < 0 || exchangeId < 0) {
            globalKill = true;
        } else if (listingId != 0) {
            if (strategyId == 0) {
                killedListings.add(listingId);
            } else {
                killedStrategyListings.add(pairKey(strategyId, listingId));
            }
        } else if (exchangeId != 0) {
            if (strategyId == 0) {
                killedExchanges.add(exchangeId);
            } else {
                killedStrategyExchanges.add(pairKey(strategyId, exchangeId));
            }
        } else if (strategyId != 0) {
            killedStrategies.add(strategyId);
        } else {
            globalKill = true;
        }
    }

    static long pairKey(final int strategyId, final int scopeId) {
        return ((long) strategyId << Integer.SIZE) | Integer.toUnsignedLong(scopeId);
    }

    static int pairStrategy(final long key) {
        return (int) (key >>> Integer.SIZE);
    }

    static int pairScope(final long key) {
        return (int) key;
    }

    /** Whether everything must stop, whatever its scope. */
    boolean stopsEverything() {
        return globalKill || unguarded;
    }

    boolean hasKills() {
        return stopsEverything()
                || !killedStrategies.isEmpty()
                || !killedExchanges.isEmpty()
                || !killedListings.isEmpty()
                || !killedStrategyExchanges.isEmpty()
                || !killedStrategyListings.isEmpty();
    }

    boolean blocks(final int strategyId, final int listingId, final int exchangeId) {
        return stopsEverything()
                || killedStrategies.contains(strategyId)
                || killedListings.contains(listingId)
                || (!killedExchanges.isEmpty() && killedExchanges.contains(exchangeId))
                || (!killedStrategyExchanges.isEmpty()
                        && killedStrategyExchanges.contains(pairKey(strategyId, exchangeId)))
                || (!killedStrategyListings.isEmpty()
                        && killedStrategyListings.contains(pairKey(strategyId, listingId)));
    }

    /** Whether a kill switch holds this exact scope, so a latch there is already recorded. */
    boolean killsExactly(final int strategyId, final int exchangeId, final int listingId) {
        if (listingId != 0) {
            return killedStrategyListings.contains(pairKey(strategyId, listingId));
        }
        if (exchangeId != 0) {
            return killedStrategyExchanges.contains(pairKey(strategyId, exchangeId));
        }
        return killedStrategies.contains(strategyId);
    }

    boolean hasMarketPolicies() {
        return marketGroups.global.count > 0 || marketGroups.hasScoped();
    }
}
