package group.gnometrading.oms.risk.util;

import group.gnometrading.codecs.json.JsonDecoder;
import java.nio.ByteBuffer;

public final class PolicyParameters {

    private PolicyParameters() {}

    @SuppressWarnings("checkstyle:NestedTryDepth")
    public static long parseLong(final JsonDecoder decoder, final ByteBuffer buffer, final String key) {
        try (var node = decoder.wrap(buffer)) {
            try (var object = node.asObject()) {
                while (object.hasNextKey()) {
                    try (var entry = object.nextKey()) {
                        if (entry.getName().equals(key)) {
                            return entry.asLong();
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Parameter '" + key + "' not found in buffer");
    }

    @SuppressWarnings("checkstyle:NestedTryDepth")
    public static double parseDouble(final JsonDecoder decoder, final ByteBuffer buffer, final String key) {
        try (var node = decoder.wrap(buffer)) {
            try (var object = node.asObject()) {
                while (object.hasNextKey()) {
                    try (var entry = object.nextKey()) {
                        if (entry.getName().equals(key)) {
                            return entry.asDouble();
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Parameter '" + key + "' not found in buffer");
    }
}
