package group.gnometrading.oms.risk;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.oms.intent.VenueCapabilities;
import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.SecurityType;
import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.IntHashSet;

/**
 * What risk needs to know about the session's listings, built once at startup so the hot path never asks the
 * security master. Read-only once built, so any thread may read it.
 */
public final class ListingDirectory {

    /** No listings: every lookup misses. For tests and backtests whose policies don't depend on the venue. */
    public static final ListingDirectory EMPTY = new ListingDirectory(List.of());

    /** What {@link #exchangeOf} returns for a listing it doesn't know; no exchange has this id. */
    public static final int NO_EXCHANGE = 0;

    private static final int[] NONE = new int[0];

    private final int[] listingIds;
    private final int[] exchangeIds;
    private final IntToIntHashMap exchangeByListing;
    private final IntHashSet binaryListings = new IntHashSet();
    private final IntHashSet longOnlyListings = new IntHashSet();
    private final IntHashMap<int[]> listingsByExchange = new IntHashMap<>();

    public ListingDirectory(final List<Listing> listings) {
        this.listingIds = new int[listings.size()];
        this.exchangeByListing = new IntToIntHashMap(Math.max(1, listings.size()));
        final List<Integer> exchanges = new ArrayList<>();
        for (int i = 0; i < listings.size(); i++) {
            final Listing listing = listings.get(i);
            final int listingId = listing.listingId();
            final int exchangeId = listing.exchange().exchangeId();
            listingIds[i] = listingId;
            exchangeByListing.put(listingId, exchangeId);
            if (paysOneOrNothing(listing.security())) {
                binaryListings.add(listingId);
            }
            if (!VenueCapabilities.supportsShortSelling(listing.exchange().exchangeCode())) {
                longOnlyListings.add(listingId);
            }
            if (!exchanges.contains(exchangeId)) {
                exchanges.add(exchangeId);
            }
        }
        this.exchangeIds = new int[exchanges.size()];
        for (int i = 0; i < exchanges.size(); i++) {
            final int exchangeId = exchanges.get(i);
            exchangeIds[i] = exchangeId;
            int count = 0;
            for (final Listing listing : listings) {
                if (listing.exchange().exchangeId() == exchangeId) {
                    count++;
                }
            }
            final int[] onExchange = new int[count];
            int next = 0;
            for (final Listing listing : listings) {
                if (listing.exchange().exchangeId() == exchangeId) {
                    onExchange[next++] = listing.listingId();
                }
            }
            listingsByExchange.put(exchangeId, onExchange);
        }
    }

    private static boolean paysOneOrNothing(final Security security) {
        return security != null
                && security.type() == SecurityType.EVENT_CONTRACT
                && (security.contractType() == ContractType.BINARY
                        || security.contractType() == ContractType.MULTI_OUTCOME);
    }

    /** The exchange a listing trades on, or {@link #NO_EXCHANGE} for a listing this session doesn't trade. */
    public int exchangeOf(final int listingId) {
        final int exchangeId = exchangeByListing.get(listingId);
        return exchangeId == IntToIntHashMap.MISSING ? NO_EXCHANGE : exchangeId;
    }

    /** Whether the listing pays $1 or nothing, so a position can never lose more than it cost. */
    public boolean isBinary(final int listingId) {
        return binaryListings.contains(listingId);
    }

    /** Whether a strategy may only sell what it holds on this listing, never go short. */
    public boolean isLongOnly(final int listingId) {
        return longOnlyListings.contains(listingId);
    }

    /** The session's listings on an exchange; shared, so callers must not modify it. */
    public int[] listingsOf(final int exchangeId) {
        final int[] listings = listingsByExchange.get(exchangeId);
        return listings == null ? NONE : listings;
    }

    public int listingCount() {
        return listingIds.length;
    }

    public int listingAt(final int index) {
        return listingIds[index];
    }

    /** The exchanges the session trades on, each once, in the order their first listing was given. */
    public int[] exchangeIds() {
        return exchangeIds.clone();
    }
}
