package group.gnometrading.oms.risk;

import group.gnometrading.strings.GnomeString;

public enum RiskPolicyType {
    KILL_SWITCH(Category.ORDER),
    MAX_NOTIONAL(Category.ORDER),
    MAX_ORDER_SIZE(Category.ORDER),
    MAX_POSITION(Category.ORDER),
    MAX_PNL_LOSS(Category.MARKET),
    MAX_TOTAL_PNL_LOSS(Category.MARKET);

    public enum Category {
        ORDER,
        MARKET
    }

    private final Category category;

    RiskPolicyType(final Category category) {
        this.category = category;
    }

    public Category category() {
        return this.category;
    }

    public static RiskPolicyType fromString(final GnomeString type) {
        if (type.equals("KILL_SWITCH")) {
            return KILL_SWITCH;
        } else if (type.equals("MAX_NOTIONAL")) {
            return MAX_NOTIONAL;
        } else if (type.equals("MAX_ORDER_SIZE")) {
            return MAX_ORDER_SIZE;
        } else if (type.equals("MAX_POSITION")) {
            return MAX_POSITION;
        } else if (type.equals("MAX_PNL_LOSS")) {
            return MAX_PNL_LOSS;
        } else if (type.equals("MAX_TOTAL_PNL_LOSS")) {
            return MAX_TOTAL_PNL_LOSS;
        }
        return null;
    }
}
