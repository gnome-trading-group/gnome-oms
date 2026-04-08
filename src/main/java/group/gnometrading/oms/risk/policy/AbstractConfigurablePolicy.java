package group.gnometrading.oms.risk.policy;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.oms.risk.Configurable;
import group.gnometrading.strings.GnomeString;
import java.nio.ByteBuffer;

public abstract class AbstractConfigurablePolicy implements Configurable {

    private static final int DEFAULT_BUFFER_CAPACITY = 512;

    protected final JsonDecoder jsonDecoder = new JsonDecoder();
    private final ByteBuffer parameterBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_CAPACITY);

    protected final ByteBuffer wrapParameters(final GnomeString parametersJson) {
        final int length = parametersJson.length();
        // Reset limit to capacity before put — put(int, byte[], ...) checks against limit, not capacity
        parameterBuffer.limit(parameterBuffer.capacity());
        parameterBuffer.put(0, parametersJson.getBytes(), parametersJson.offset(), length);
        parameterBuffer.limit(length).position(0);
        return parameterBuffer;
    }
}
