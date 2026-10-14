package group.gnometrading.oms.risk;

import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.Long2LongHashMap;

/**
 * How many venue rejects in a row each strategy has had: across all its listings, on each exchange, and on each
 * listing. Owned by the {@link RiskEngine} rather than a snapshot, so a policy refresh never forgets a streak.
 * OMS thread only.
 *
 * <p>Sized up front for more strategies and listings than a session trades, so recording never allocates; a streak
 * is removed rather than zeroed when it resets, which keeps the common case — no rejects at all — a few checks.
 */
public final class RejectStreaks {

    private static final int EXPECTED_STRATEGIES = 16;
    private static final int EXPECTED_SCOPES = 256;
    private static final int NONE = 0;

    private final Int2IntHashMap byStrategy = new Int2IntHashMap(EXPECTED_STRATEGIES, 0.65f, NONE);
    private final Long2LongHashMap byStrategyExchange = new Long2LongHashMap(EXPECTED_SCOPES, 0.65f, NONE);
    private final Long2LongHashMap byStrategyListing = new Long2LongHashMap(EXPECTED_SCOPES, 0.65f, NONE);

    /** Counts a venue reject for the strategy on this listing, in every scope that covers it. */
    public void recordReject(final int strategyId, final int exchangeId, final int listingId) {
        byStrategy.put(strategyId, byStrategy.get(strategyId) + 1);
        final long exchangeKey = RiskEngineSnapshot.pairKey(strategyId, exchangeId);
        byStrategyExchange.put(exchangeKey, byStrategyExchange.get(exchangeKey) + 1);
        final long listingKey = RiskEngineSnapshot.pairKey(strategyId, listingId);
        byStrategyListing.put(listingKey, byStrategyListing.get(listingKey) + 1);
    }

    /** The venue took an order or filled one, so every streak covering it starts again. */
    public void reset(final int strategyId, final int exchangeId, final int listingId) {
        if (byStrategy.isEmpty() && byStrategyExchange.isEmpty() && byStrategyListing.isEmpty()) {
            return;
        }
        byStrategy.remove(strategyId);
        byStrategyExchange.remove(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
        byStrategyListing.remove(RiskEngineSnapshot.pairKey(strategyId, listingId));
    }

    /**
     * A latch has halted this scope, so its streaks are over: an operator resuming it starts from nothing. A scope
     * id of 0 covers all of them; the strategy-wide streak always ends, since the rejects it counted are the ones
     * that tripped the latch.
     */
    public void clear(final int strategyId, final int exchangeId, final int listingId) {
        byStrategy.remove(strategyId);
        if (exchangeId == 0 && listingId == 0) {
            clearStrategy(byStrategyExchange, strategyId);
            clearStrategy(byStrategyListing, strategyId);
            return;
        }
        if (exchangeId != 0) {
            byStrategyExchange.remove(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
        }
        if (listingId != 0) {
            byStrategyListing.remove(RiskEngineSnapshot.pairKey(strategyId, listingId));
        }
    }

    private static void clearStrategy(final Long2LongHashMap streaks, final int strategyId) {
        final Long2LongHashMap.KeyIterator keys = streaks.keySet().iterator();
        while (keys.hasNext()) {
            if (RiskEngineSnapshot.pairStrategy(keys.nextValue()) == strategyId) {
                keys.remove();
            }
        }
    }

    public long ofStrategy(final int strategyId) {
        return byStrategy.get(strategyId);
    }

    public long ofExchange(final int strategyId, final int exchangeId) {
        return byStrategyExchange.get(RiskEngineSnapshot.pairKey(strategyId, exchangeId));
    }

    public long ofListing(final int strategyId, final int listingId) {
        return byStrategyListing.get(RiskEngineSnapshot.pairKey(strategyId, listingId));
    }
}
