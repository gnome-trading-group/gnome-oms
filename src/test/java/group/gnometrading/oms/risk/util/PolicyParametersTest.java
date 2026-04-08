package group.gnometrading.oms.risk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.codecs.json.JsonDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolicyParametersTest {

    private final JsonDecoder decoder = new JsonDecoder();

    private static ByteBuffer wrapJson(final String json) {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(bytes.length);
        buf.put(bytes).flip();
        return buf;
    }

    @Test
    void testParseLongFindsKey() {
        final ByteBuffer buf = wrapJson("{\"maxOrderSize\": 42}");
        assertEquals(42L, PolicyParameters.parseLong(decoder, buf, "maxOrderSize"));
    }

    @Test
    void testParseLongThrowsWhenKeyNotFound() {
        final ByteBuffer buf = wrapJson("{\"other\": 42}");
        assertThrows(IllegalStateException.class, () -> PolicyParameters.parseLong(decoder, buf, "maxOrderSize"));
    }

    @Test
    void testParseLongWithMultipleKeys() {
        final ByteBuffer buf = wrapJson("{\"a\": 1, \"maxOrderSize\": 42, \"b\": 3}");
        assertEquals(42L, PolicyParameters.parseLong(decoder, buf, "maxOrderSize"));
    }

    @Test
    void testParseDoubleFindsKey() {
        final ByteBuffer buf = wrapJson("{\"maxLoss\": 99.5}");
        assertEquals(99.5, PolicyParameters.parseDouble(decoder, buf, "maxLoss"), 0.0001);
    }

    @Test
    void testParseDoubleThrowsWhenKeyNotFound() {
        final ByteBuffer buf = wrapJson("{\"other\": 99.5}");
        assertThrows(IllegalStateException.class, () -> PolicyParameters.parseDouble(decoder, buf, "maxLoss"));
    }
}
