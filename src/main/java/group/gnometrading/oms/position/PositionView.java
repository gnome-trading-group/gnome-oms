package group.gnometrading.oms.position;

/**
 * Read-only view of position state for a single strategy.
 *
 * <p>In live trading, backed by a {@link SharedPositionBuffer} updated by the OMS thread.
 * In backtesting, backed directly by the OMS PositionTracker (same thread).
 */
public interface PositionView {

    Position getPosition(int listingId);

    long getEffectiveQuantity(int listingId);
}
