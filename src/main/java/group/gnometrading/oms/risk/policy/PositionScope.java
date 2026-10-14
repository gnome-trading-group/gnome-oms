package group.gnometrading.oms.risk.policy;

import group.gnometrading.oms.risk.ListingDirectory;

/**
 * Which of a strategy's positions a limit that adds up across listings judges: one listing's alone when the policy
 * names a listing, otherwise every listing the strategy trades on the policy's exchange, or on any exchange.
 */
final class PositionScope {

    private final ListingDirectory directory;
    private final boolean acrossListings;
    private final int exchangeId;

    PositionScope(final ListingDirectory directory, final int listingId, final int exchangeId) {
        this.directory = directory;
        this.acrossListings = listingId == 0;
        this.exchangeId = acrossListings ? exchangeId : 0;
    }

    boolean acrossListings() {
        return acrossListings;
    }

    /** Whether a position on {@code listingId} counts towards a limit across listings. */
    boolean covers(final int listingId) {
        return exchangeId == 0 || directory.exchangeOf(listingId) == exchangeId;
    }
}
