package group.gnometrading.oms.risk;

import java.util.ArrayList;
import java.util.List;

/** Publishes kill-switch state the way {@link RiskSyncAgent} would, for tests outside this package. */
public final class RiskSnapshots {

    private RiskSnapshots() {}

    public static void publishKills(
            final RiskEngine engine, final boolean global, final int[] strategyIds, final int[] listingIds) {
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.globalKill = global;
        for (final int strategyId : strategyIds) {
            snapshot.killedStrategies.add(strategyId);
        }
        for (final int listingId : listingIds) {
            snapshot.killedListings.add(listingId);
        }
        engine.publishSnapshot(snapshot);
    }

    /** Kills each exchange outright, for every strategy. */
    public static void publishExchangeKills(final RiskEngine engine, final int... exchangeIds) {
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        for (final int exchangeId : exchangeIds) {
            snapshot.killedExchanges.add(exchangeId);
        }
        engine.publishSnapshot(snapshot);
    }

    /** Marks the session as missing a required policy, which stops everything. */
    public static void publishUnguarded(final RiskEngine engine) {
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        snapshot.unguarded = true;
        engine.publishSnapshot(snapshot);
    }

    /** Kills each {strategyId, listingId} pair: that strategy on that listing only. */
    public static void publishStrategyListingKills(final RiskEngine engine, final int[]... pairs) {
        final RiskEngineSnapshot snapshot = new RiskEngineSnapshot();
        for (final int[] pair : pairs) {
            snapshot.killedStrategyListings.add(RiskEngineSnapshot.pairKey(pair[0], pair[1]));
        }
        engine.publishSnapshot(snapshot);
    }

    public static void publishNoKills(final RiskEngine engine) {
        engine.publishSnapshot(new RiskEngineSnapshot());
    }

    /** Takes the halts latched since the last call, as the sync thread would. */
    public static List<Integer> drainLatchedHalts(final RiskEngine engine) {
        final List<Integer> halts = new ArrayList<>();
        engine.drainLatchedHalts(halt -> halts.add(halt.strategyId));
        return halts;
    }

    /** As {@link #drainLatchedHalts}, describing each halt as "strategy/exchange/listing cause policy rejects". */
    public static List<String> drainLatchedHaltScopes(final RiskEngine engine) {
        final List<String> halts = new ArrayList<>();
        engine.drainLatchedHalts(halt -> halts.add(halt.strategyId + "/" + halt.exchangeId + "/" + halt.listingId
                + " cause " + halt.cause + " policy " + halt.policyId + " rejects " + halt.rejects));
        return halts;
    }

    public static void recordRefresh(final RiskEngine engine, final long nowMs) {
        engine.recordRefresh(nowMs);
    }
}
