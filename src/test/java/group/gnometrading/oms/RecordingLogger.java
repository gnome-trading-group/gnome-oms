package group.gnometrading.oms;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/** Keeps each line as {@code MESSAGE v1 v2 ...}, for tests that check what was logged. */
final class RecordingLogger implements Logger {

    final List<String> lines = new ArrayList<>();

    List<String> linesFor(final LogMessage msg) {
        return lines.stream().filter(line -> line.startsWith(msg.name())).toList();
    }

    private void record(final LogMessage msg, final long... values) {
        lines.add(msg.name()
                + LongStream.of(values).mapToObj(value -> " " + value).collect(Collectors.joining()));
    }

    @Override
    public void log(final LogMessage msg) {
        record(msg);
    }

    @Override
    public void log(final LogMessage msg, final long v1) {
        record(msg, v1);
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2) {
        record(msg, v1, v2);
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3) {
        record(msg, v1, v2, v3);
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {
        record(msg, v1, v2, v3, v4);
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4, final long v5) {
        record(msg, v1, v2, v3, v4, v5);
    }

    @Override
    public void log(
            final LogMessage msg,
            final long v1,
            final long v2,
            final long v3,
            final long v4,
            final long v5,
            final long v6) {
        record(msg, v1, v2, v3, v4, v5, v6);
    }

    @Override
    public void logf(final LogMessage msg, final String format, final Object... args) {
        lines.add(msg.name() + " " + String.format(format, args));
    }
}
