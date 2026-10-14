package group.gnometrading.oms.risk;

/** The tightest {@link group.gnometrading.oms.risk.policy.MarketOrderSlippagePolicy} set on one scope. */
final class SlippageLimit {

    static final long NONE = Long.MAX_VALUE;

    long maxSlippage = NONE;

    void tighten(final long slippage) {
        maxSlippage = Math.min(maxSlippage, slippage);
    }
}
