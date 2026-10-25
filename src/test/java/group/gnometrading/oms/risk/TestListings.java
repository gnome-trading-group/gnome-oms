package group.gnometrading.oms.risk;

import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.SecurityType;
import java.util.List;

/** Listings for building a {@link ListingDirectory} in tests. */
public final class TestListings {

    private TestListings() {}

    /** A YES/NO event contract on an exchange where strategies may go short. */
    public static Listing binary(final int listingId, final int exchangeId) {
        return listing(listingId, exchangeId, "KALSHI", SecurityType.EVENT_CONTRACT, ContractType.BINARY);
    }

    /** A YES/NO event contract on Polymarket International, where strategies may only sell what they hold. */
    public static Listing longOnlyBinary(final int listingId, final int exchangeId) {
        return listing(listingId, exchangeId, "POLYMARKET_INTL", SecurityType.EVENT_CONTRACT, ContractType.BINARY);
    }

    /** A perpetual, valued at gross notional. */
    public static Listing perpetual(final int listingId, final int exchangeId) {
        return listing(listingId, exchangeId, "HYPERLIQUID", SecurityType.PERPETUAL, ContractType.LINEAR_PERPETUAL);
    }

    public static Listing listing(
            final int listingId,
            final int exchangeId,
            final String exchangeCode,
            final SecurityType type,
            final ContractType contractType) {
        return new Listing(
                listingId,
                new Exchange(exchangeId, exchangeCode, exchangeCode, "us-east-1"),
                new Security(
                        listingId, "SYM", type, contractType, null, null, null, null, false, false, 0L, 0L, true, 0),
                "SYM",
                "SYM");
    }

    public static ListingDirectory directory(final Listing... listings) {
        return new ListingDirectory(List.of(listings));
    }
}
