package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;

class MarketOrderSlippagePolicyTest {

    @Test
    void readsTheSlippageInPriceUnits() {
        final MarketOrderSlippagePolicy policy = new MarketOrderSlippagePolicy();
        policy.reconfigure(new ViewString("{\"maxSlippage\": 20000000}"));
        assertEquals(20_000_000L, policy.maxSlippage());
    }

    @Test
    void refusesANegativeSlippage() {
        final MarketOrderSlippagePolicy policy = new MarketOrderSlippagePolicy();
        assertThrows(IllegalStateException.class, () -> policy.reconfigure(new ViewString("{\"maxSlippage\": -1}")));
    }
}
