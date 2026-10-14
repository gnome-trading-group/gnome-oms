package group.gnometrading.oms.intent;

/**
 * What a venue's order-entry API supports, keyed by registry exchange code.
 *
 * <p>Lives in code rather than the registry because it describes the gateway implementations: a venue
 * only gains native modify when its gateway does, so the two must change together.
 */
public final class VenueCapabilities {

    private VenueCapabilities() {}

    /**
     * Whether a working order's price and size can be changed in place. Venues not listed here get
     * cancel-then-new, which is correct everywhere, so an unclassified venue fails safe.
     */
    public static boolean supportsNativeModify(final String exchangeCode) {
        if (exchangeCode == null) {
            return false;
        }
        return switch (exchangeCode) {
            case "KALSHI", "BINANCE", "HYPERLIQUID", "LIGHTER", "POLYMARKET_US" -> true;
            default -> false;
        };
    }

    /**
     * Whether selling more than the account holds opens a short: Kalshi books it as a NO, Polymarket US holds its
     * payout as margin, and the perpetual venues have no inventory to run out of. Elsewhere it's refused or sells
     * someone else's: a Polymarket International wallet is shared by strategies, so one selling beyond what it holds
     * would sell another's tokens. Venues not listed here may only sell what is held, so an unclassified venue fails
     * safe.
     */
    public static boolean supportsShortSelling(final String exchangeCode) {
        if (exchangeCode == null) {
            return false;
        }
        return switch (exchangeCode) {
            case "KALSHI", "POLYMARKET_US", "HYPERLIQUID", "LIGHTER" -> true;
            default -> false;
        };
    }
}
