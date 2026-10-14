package group.gnometrading.oms.risk;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.policy.MaxCommittedCapitalPolicy;
import group.gnometrading.oms.risk.policy.MaxConsecutiveRejectsPolicy;
import group.gnometrading.oms.risk.policy.MaxOpenOrdersPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderNotionalPolicy;
import group.gnometrading.oms.risk.policy.MaxOrderSizePolicy;
import group.gnometrading.oms.risk.policy.MaxPositionPolicy;
import group.gnometrading.oms.risk.policy.MaxTotalPnlLossPolicy;
import group.gnometrading.oms.risk.policy.PriceCollarPolicy;

/**
 * Instantiates concrete risk policy objects by {@link RiskPolicyType}.
 *
 * <p>Policies that value positions or orders at market ({@link RiskPolicyType#PRICE_COLLAR},
 * {@link RiskPolicyType#MAX_TOTAL_PNL_LOSS}) need a {@link SharedPriceBuffer} and {@link PriceSlotRegistry}. The
 * {@link ListingDirectory} and {@link RejectStreaks} must be the ones the {@link RiskEngine} the policies run in holds,
 * so pass {@link RiskEngine#directory()} and {@link RiskEngine#rejectStreaks()}.
 */
public final class PolicyFactory {

    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final ListingDirectory directory;
    private final RejectStreaks rejectStreaks;

    public PolicyFactory(
            final SharedPriceBuffer priceBuffer,
            final PriceSlotRegistry priceSlotRegistry,
            final ListingDirectory directory,
            final RejectStreaks rejectStreaks) {
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.directory = directory;
        this.rejectStreaks = rejectStreaks;
    }

    /**
     * Creates a new, unconfigured policy for a registry row's target; 0 is every listing or exchange. A policy that
     * names no listing judges limits that add up across listings (committed capital, total PnL loss, open orders)
     * over the strategy's total: on the row's exchange when it names one, or everywhere otherwise.
     *
     * <p>Callers should invoke {@link Configurable#reconfigure} with a JSON parameter string before adding the policy
     * to a {@link RiskEngine}.
     */
    public Configurable create(final RiskPolicyType type, final int listingId, final int exchangeId) {
        return switch (type) {
            case KILL_SWITCH -> throw new IllegalArgumentException("KILL_SWITCH is a kill, not a policy");
            case MAX_ORDER_NOTIONAL -> new MaxOrderNotionalPolicy(priceBuffer, priceSlotRegistry);
            case MAX_ORDER_SIZE -> new MaxOrderSizePolicy();
            case MAX_POSITION -> new MaxPositionPolicy();
            case MAX_OPEN_ORDERS -> new MaxOpenOrdersPolicy(directory, listingId, exchangeId);
            case MAX_COMMITTED_CAPITAL -> new MaxCommittedCapitalPolicy(directory, listingId, exchangeId);
            case MAX_CONSECUTIVE_REJECTS -> new MaxConsecutiveRejectsPolicy(rejectStreaks, listingId, exchangeId);
            case PRICE_COLLAR -> {
                if (priceBuffer == null || priceSlotRegistry == null) {
                    throw new IllegalArgumentException("PRICE_COLLAR requires SharedPriceBuffer and PriceSlotRegistry");
                }
                yield new PriceCollarPolicy(priceBuffer, priceSlotRegistry);
            }
            case MAX_TOTAL_PNL_LOSS -> {
                if (priceBuffer == null || priceSlotRegistry == null) {
                    throw new IllegalArgumentException(
                            "MAX_TOTAL_PNL_LOSS requires SharedPriceBuffer and PriceSlotRegistry");
                }
                yield new MaxTotalPnlLossPolicy(priceBuffer, priceSlotRegistry, directory, listingId, exchangeId);
            }
        };
    }
}
