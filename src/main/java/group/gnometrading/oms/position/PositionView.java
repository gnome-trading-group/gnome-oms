package group.gnometrading.oms.position;

/**
 * Read-only view of position state for a single strategy.
 *
 * <p>In live trading, backed by a {@link SharedPositionBuffer} updated by the OMS thread.
 * In backtesting, backed directly by the OMS PositionTracker (same thread).
 */
public interface PositionView {

    /** @throws IllegalArgumentException if {@code listingId} was not registered when the view was created */
    Position getPosition(int listingId);
}
