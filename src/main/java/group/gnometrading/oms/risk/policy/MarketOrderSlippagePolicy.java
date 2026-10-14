package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.risk.Configurable;
import group.gnometrading.oms.risk.util.PolicyParameters;
import group.gnometrading.strings.GnomeString;

/**
 * How far past the touch a market order may fill, in price units (1e9 per dollar). No venue takes a market order
 * without a price, so the OMS sends one as an immediate-or-cancel limit this far past the best opposite price. Not a
 * check: it sets the price every other check then judges.
 */
public final class MarketOrderSlippagePolicy extends AbstractConfigurablePolicy implements Configurable {

    private long maxSlippage;

    public MarketOrderSlippagePolicy() {}

    public MarketOrderSlippagePolicy(final long maxSlippage) {
        this.maxSlippage = maxSlippage;
    }

    @Override
    public void reconfigure(final GnomeString parametersJson) {
        final long parsed = PolicyParameters.parseLong(jsonDecoder, wrapParameters(parametersJson), "maxSlippage");
        if (parsed < 0) {
            throw new IllegalStateException("maxSlippage must not be negative, got " + parsed);
        }
        this.maxSlippage = parsed;
    }

    public long maxSlippage() {
        return maxSlippage;
    }
}
