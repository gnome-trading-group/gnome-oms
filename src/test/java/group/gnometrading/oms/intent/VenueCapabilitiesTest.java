package group.gnometrading.oms.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VenueCapabilitiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"KALSHI", "BINANCE", "HYPERLIQUID", "LIGHTER"})
    void venuesWithAmendSupportNativeModify(String exchangeCode) {
        assertTrue(VenueCapabilities.supportsNativeModify(exchangeCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POLYMARKET_INTL", "POLYMARKET_US", "SOMETHING_NEW", "kalshi", ""})
    void everythingElseCancelsThenSubmits(String exchangeCode) {
        assertFalse(VenueCapabilities.supportsNativeModify(exchangeCode));
    }

    @ParameterizedTest
    @NullSource
    void missingCodeCancelsThenSubmits(String exchangeCode) {
        assertFalse(VenueCapabilities.supportsNativeModify(exchangeCode));
    }
}
