package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.MarketRiskPolicy;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.strings.GnomeString;

public final class MaxPnlLossPolicy extends AbstractConfigurablePolicy implements MarketRiskPolicy {

    private long maxLoss;

    public MaxPnlLossPolicy() {}

    public MaxPnlLossPolicy(final long maxLoss) {
        this.maxLoss = maxLoss;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        this.maxLoss = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxLoss");
    }

    @Override
    public boolean isViolated(
            final int strategyId,
            final int listingId,
            final PositionTracker positions,
            final OrderStateManager orders) {
        final Position pos = positions.getStrategyPosition(strategyId, listingId);
        return pos != null && pos.realizedPnl < -maxLoss;
    }
}
