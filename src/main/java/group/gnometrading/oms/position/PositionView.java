package group.gnometrading.oms.position;

import java.util.function.Consumer;

/**
 * Read-only view of position state for a single strategy.
 *
 * In live trading, backed by a local copy updated from a fill event ring buffer.
 * In backtesting, backed directly by the OMS PositionTracker (same thread).
 */
public interface PositionView {

    Position getPosition(int exchangeId, long securityId);

    void forEachPosition(Consumer<Position> consumer);

    long getEffectiveQuantity(int exchangeId, long securityId);
}
