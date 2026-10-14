package group.gnometrading.oms.risk;

import group.gnometrading.strings.GnomeString;

public enum RiskPolicyType {
    KILL_SWITCH(Category.KILL),
    MAX_ORDER_NOTIONAL(Category.ORDER),
    MAX_ORDER_SIZE(Category.ORDER),
    MAX_POSITION(Category.ORDER),
    MAX_OPEN_ORDERS(Category.ORDER),
    PRICE_COLLAR(Category.ORDER),
    MAX_COMMITTED_CAPITAL(Category.ORDER),
    MAX_TOTAL_PNL_LOSS(Category.MARKET),
    MAX_CONSECUTIVE_REJECTS(Category.MARKET),
    MARKET_ORDER_SLIPPAGE(Category.PRICING);

    public enum Category {
        ORDER,
        MARKET,
        /** Not a check: a value that prices orders before the checks judge them. */
        PRICING,
        /** Not a policy: an enabled row blocks its target and cancels everything in it. */
        KILL
    }

    // values() copies its array on every call.
    private static final RiskPolicyType[] ALL = values();

    private final Category category;

    RiskPolicyType(final Category category) {
        this.category = category;
    }

    public Category category() {
        return this.category;
    }

    public static RiskPolicyType fromString(final GnomeString type) {
        for (final RiskPolicyType candidate : ALL) {
            if (type.equals(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }
}
