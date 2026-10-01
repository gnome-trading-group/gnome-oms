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
            case "KALSHI", "BINANCE", "HYPERLIQUID", "LIGHTER" -> true;
            default -> false;
        };
    }
}
