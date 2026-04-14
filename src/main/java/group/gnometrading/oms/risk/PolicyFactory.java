package group.gnometrading.oms.risk;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.policy.AutoDenyPolicy;
import group.gnometrading.oms.risk.policy.MaxNotionalValuePolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxPnlLossPolicy;
import group.gnometrading.oms.risk.policy.MaxPositionPolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;

/**
 * Instantiates concrete risk policy objects by {@link RiskPolicyType}.
 *
 * <p>Use the no-arg constructor when {@link RiskPolicyType#MAX_TOTAL_PNL_LOSS} is not needed
 * (e.g. backtest context). Pass a {@link SharedPriceBuffer} and {@link PriceSlotRegistry} to
 * support all policy types including live mark-to-market PnL policies.
 */
public final class PolicyFactory {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;

    public PolicyFactory() {
        this(null, null);
    }

    public PolicyFactory(final SharedPriceBuffer priceBuffer, final PriceSlotRegistry priceSlotRegistry) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
    }

    /**
     * Creates a new, unconfigured policy instance for the given type.
     *
     * <p>Callers should invoke {@link Configurable#reconfigure} with a JSON parameter string
     * before adding the policy to a {@link RiskEngine}.
     */
    public Configurable create(final RiskPolicyType type) {
        return switch (type) {
            case KILL_SWITCH -> new AutoDenyPolicy();
            case MAX_NOTIONAL -> new MaxNotionalValuePolicy();
            case MAX_ORDER_SIZE -> new MaxOrderSizePolicy();
            case MAX_POSITION -> new MaxPositionPolicy();
            case MAX_PNL_LOSS -> new MaxPnlLossPolicy();
            case MAX_TOTAL_PNL_LOSS -> {
                if (priceBuffer == null || priceSlotRegistry == null) {
                    throw new IllegalArgumentException(
                            "MAX_TOTAL_PNL_LOSS requires SharedPriceBuffer and PriceSlotRegistry");
                }
                yield new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry);
            }
        };
    }
}
