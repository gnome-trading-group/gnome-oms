package group.gnometrading.oms.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.oms.position.Position;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the cases the registry's TypeScript mirror shares, so the two can't drift apart. */
class CommittedCapitalTest {

    private static final String CASES = "/committed-capital-cases.json";

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedCases")
    void testSharedCase(final String name, final JsonNode fixture) {
        final long committed = CommittedCapital.of(
                fixture.get("binary").asBoolean(),
                number(fixture, "netQuantity"),
                number(fixture, "totalCost"),
                number(fixture, "leavesBuyQty"),
                number(fixture, "leavesBuyNotional"),
                number(fixture, "leavesSellQty"),
                number(fixture, "leavesSellNotional"));
        assertEquals(number(fixture, "expected"), committed, name);
    }

    @Test
    void testAPositionIsValuedFromItsFields() {
        final Position position = new Position();
        position.init(1);
        position.netQuantity = -30_000_000L;
        position.totalCost = 12_000_000_000L;
        position.leavesBuyQty = 50_000_000L;
        position.leavesBuyNotional = 30_000_000_000L;
        assertEquals(30_000_000_000L, CommittedCapital.of(true, position));
        assertEquals(42_000_000_000L, CommittedCapital.of(false, position));
    }

    @Test
    void testTheSharedCasesExist() throws IOException {
        assertFalse(loadCases().isEmpty());
    }

    static Stream<Arguments> sharedCases() throws IOException {
        final List<Arguments> arguments = new ArrayList<>();
        for (final JsonNode fixture : loadCases()) {
            arguments.add(Arguments.of(fixture.get("name").asText(), fixture));
        }
        return arguments.stream();
    }

    private static List<JsonNode> loadCases() throws IOException {
        try (InputStream in = CommittedCapitalTest.class.getResourceAsStream(CASES)) {
            final List<JsonNode> cases = new ArrayList<>();
            new ObjectMapper().readTree(in).get("cases").forEach(cases::add);
            return cases;
        }
    }

    private static long number(final JsonNode fixture, final String field) {
        return Long.parseLong(fixture.get(field).asText());
    }
}
