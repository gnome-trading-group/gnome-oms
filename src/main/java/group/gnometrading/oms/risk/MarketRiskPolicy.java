package group.gnometrading.oms.risk;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;

public interface MarketRiskPolicy extends Configurable {

    /** Passed as {@code listingId} to judge a strategy's total across every listing it trades. */
    int ALL_LISTINGS = Integer.MIN_VALUE;

    /** Judges one (strategy, listing) position, or the strategy's total when {@code listingId} is {@link #ALL_LISTINGS}. */
    boolean isViolated(int strategyId, int listingId, PositionTracker positions, OrderStateManager orders);
}
