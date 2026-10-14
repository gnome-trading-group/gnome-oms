package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.RejectStreaks;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.strings.GnomeString;

/**
 * Breached once the venue has rejected {@code maxRejects} of the strategy's orders in a row: on the listing for a
 * listing-scoped limit, on the policy's exchange for an exchange-scoped one, or anywhere otherwise. A circuit
 * breaker for a strategy stuck resubmitting what the venue will never take; which rejects count is the OMS's
 * concern, as {@link RejectStreaks} is told only of those.
 */
public final class MaxConsecutiveRejectsPolicy extends AbstractConfigurablePolicy implements MarketRiskPolicy {

    private final RejectStreaks streaks;
    private final boolean perListing;
    private final int policyExchangeId;
    private long maxRejects;

    /** {@code listingId} and {@code exchangeId} are the policy's target; 0 is every listing or exchange. */
    public MaxConsecutiveRejectsPolicy(final RejectStreaks streaks, final int listingId, final int exchangeId) {
        this.streaks = streaks;
        this.perListing = listingId != 0;
        this.policyExchangeId = perListing ? 0 : exchangeId;
    }

    public MaxConsecutiveRejectsPolicy(
            final RejectStreaks streaks, final int listingId, final int exchangeId, final long maxRejects) {
        this(streaks, listingId, exchangeId);
        this.maxRejects = maxRejects;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        final long parsed = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxRejects");
        if (parsed <= 0) {
            throw new IllegalArgumentException("maxRejects must be positive, got " + parsed);
        }
        this.maxRejects = parsed;
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        return streak(strategyId, listingId) >= maxRejects;
    }

    private long streak(final int strategyId, final int listingId) {
        if (perListing) {
            return streaks.ofListing(strategyId, listingId);
        }
        // Only orders on the policy's own exchange are judged by it, so its exchange is the order's.
        if (policyExchangeId != 0) {
            return streaks.ofExchange(strategyId, policyExchangeId);
        }
        return streaks.ofStrategy(strategyId);
    }
}
