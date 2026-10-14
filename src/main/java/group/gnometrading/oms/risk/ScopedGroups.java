package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.LongHashMap;
import java.util.function.Supplier;

/**
 * One policy group per target a registry row can name: everything, one exchange, one strategy, one strategy on one
 * exchange, one strategy on one listing, or one listing. Built on the sync thread; read-only once published.
 */
final class ScopedGroups<G> {

    final G global;
    private final IntHashMap<G> byExchange = new IntHashMap<>();
    private final IntHashMap<G> byStrategy = new IntHashMap<>();
    private final LongHashMap<G> byStrategyExchange = new LongHashMap<>();
    private final LongHashMap<G> byStrategyListing = new LongHashMap<>();
    private final IntHashMap<G> byListing = new IntHashMap<>();
    private final Supplier<G> newGroup;

    ScopedGroups(final Supplier<G> newGroup) {
        this.newGroup = newGroup;
        this.global = newGroup.get();
    }

    /** The group a target selects, created if new. A listing beats an exchange, which beats no scope. */
    G groupFor(final int strategyId, final int listingId, final int exchangeId) {
        if (listingId != 0) {
            return strategyId == 0
                    ? group(byListing, listingId)
                    : group(byStrategyListing, RiskEngineSnapshot.pairKey(strategyId, listingId));
        }
        if (exchangeId != 0) {
            return strategyId == 0
                    ? group(byExchange, exchangeId)
                    : group(byStrategyExchange, RiskEngineSnapshot.pairKey(strategyId, exchangeId));
        }
        return strategyId == 0 ? global : group(byStrategy, strategyId);
    }

    private G group(final IntHashMap<G> groups, final int key) {
        G group = groups.get(key);
        if (group == null) {
            group = newGroup.get();
            groups.put(key, group);
        }
        return group;
    }

    private G group(final LongHashMap<G> groups, final long key) {
        G group = groups.get(key);
        if (group == null) {
            group = newGroup.get();
            groups.put(key, group);
        }
        return group;
    }

    /** Whether any group besides the global one exists. */
    boolean hasScoped() {
        return !byExchange.isEmpty()
                || !byStrategy.isEmpty()
                || !byStrategyExchange.isEmpty()
                || !byStrategyListing.isEmpty()
                || !byListing.isEmpty();
    }

    G exchange(final int exchangeId) {
        return byExchange.isEmpty() ? null : byExchange.get(exchangeId);
    }

    G strategy(final int strategyId) {
        return byStrategy.isEmpty() ? null : byStrategy.get(strategyId);
    }

    G strategyExchange(final int strategyId, final int exchangeId) {
        return byStrategyExchange.isEmpty()
                ? null
                : byStrategyExchange.get(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
    }

    G strategyListing(final int strategyId, final int listingId) {
        return byStrategyListing.isEmpty()
                ? null
                : byStrategyListing.get(RiskEngineSnapshot.pairKey(strategyId, listingId));
    }

    G listing(final int listingId) {
        return byListing.isEmpty() ? null : byListing.get(listingId);
    }
}
