package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.strings.ViewString;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RiskPolicyTypeTest {

    @ParameterizedTest
    @MethodSource("testFromStringArguments")
    void testFromString(final String input, final RiskPolicyType expected) {
        assertEquals(expected, RiskPolicyType.fromString(new ViewString(input)));
    }

    private static Stream<Arguments> testFromStringArguments() {
        return Stream.of(
                Arguments.of("KILL_SWITCH", RiskPolicyType.KILL_SWITCH),
                Arguments.of("MAX_NOTIONAL", RiskPolicyType.MAX_NOTIONAL),
                Arguments.of("MAX_ORDER_SIZE", RiskPolicyType.MAX_ORDER_SIZE),
                Arguments.of("MAX_POSITION", RiskPolicyType.MAX_POSITION),
                Arguments.of("MAX_PNL_LOSS", RiskPolicyType.MAX_PNL_LOSS),
                Arguments.of("UNKNOWN", null));
    }

    @ParameterizedTest
    @MethodSource("testCategoryArguments")
    void testCategory(final RiskPolicyType type, final RiskPolicyType.Category expected) {
        assertEquals(expected, type.category());
    }

    private static Stream<Arguments> testCategoryArguments() {
        return Stream.of(
                Arguments.of(RiskPolicyType.KILL_SWITCH, RiskPolicyType.Category.ORDER),
                Arguments.of(RiskPolicyType.MAX_NOTIONAL, RiskPolicyType.Category.ORDER),
                Arguments.of(RiskPolicyType.MAX_ORDER_SIZE, RiskPolicyType.Category.ORDER),
                Arguments.of(RiskPolicyType.MAX_POSITION, RiskPolicyType.Category.ORDER),
                Arguments.of(RiskPolicyType.MAX_PNL_LOSS, RiskPolicyType.Category.MARKET));
    }
}
