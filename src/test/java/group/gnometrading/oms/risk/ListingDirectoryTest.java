package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.SecurityType;
import org.junit.jupiter.api.Test;

class ListingDirectoryTest {

    private final ListingDirectory directory = TestListings.directory(
            TestListings.binary(100, 1),
            TestListings.listing(101, 1, "KALSHI", SecurityType.EVENT_CONTRACT, ContractType.MULTI_OUTCOME),
            TestListings.longOnlyBinary(200, 2),
            TestListings.perpetual(300, 3),
            TestListings.listing(301, 3, "HYPERLIQUID", SecurityType.EVENT_CONTRACT, ContractType.NONE));

    @Test
    void testExchangeOfEachListing() {
        assertEquals(1, directory.exchangeOf(100));
        assertEquals(2, directory.exchangeOf(200));
        assertEquals(3, directory.exchangeOf(301));
        assertEquals(ListingDirectory.NO_EXCHANGE, directory.exchangeOf(999));
    }

    @Test
    void testOnlyBinaryAndMultiOutcomeEventContractsAreBinary() {
        assertTrue(directory.isBinary(100));
        assertTrue(directory.isBinary(101));
        assertTrue(directory.isBinary(200));
        assertFalse(directory.isBinary(300), "a perpetual");
        assertFalse(directory.isBinary(301), "an event contract of no binary kind");
        assertFalse(directory.isBinary(999));
    }

    @Test
    void testListingsOnVenuesThatCantShortAreLongOnly() {
        assertTrue(directory.isLongOnly(200));
        assertFalse(directory.isLongOnly(100));
        assertFalse(directory.isLongOnly(300));
        assertTrue(
                TestListings.directory(TestListings.listing(
                                400, 4, "SOMETHING_NEW", SecurityType.EVENT_CONTRACT, ContractType.BINARY))
                        .isLongOnly(400),
                "an unclassified venue fails safe");
    }

    @Test
    void testListingsByExchange() {
        assertArrayEquals(new int[] {100, 101}, directory.listingsOf(1));
        assertArrayEquals(new int[] {200}, directory.listingsOf(2));
        assertArrayEquals(new int[0], directory.listingsOf(9));
        assertArrayEquals(new int[] {1, 2, 3}, directory.exchangeIds());
        assertEquals(5, directory.listingCount());
        assertEquals(300, directory.listingAt(3));
    }

    @Test
    void testTheEmptyDirectoryKnowsNothing() {
        assertEquals(0, ListingDirectory.EMPTY.listingCount());
        assertEquals(ListingDirectory.NO_EXCHANGE, ListingDirectory.EMPTY.exchangeOf(100));
        assertFalse(ListingDirectory.EMPTY.isBinary(100));
        assertFalse(ListingDirectory.EMPTY.isLongOnly(100));
    }
}
