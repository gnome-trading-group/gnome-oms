package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

public interface PositionTracker extends PositionView {

    // --- Firm-level (aggregate) ---

    Position getPosition(int listingId);

    // --- Per-strategy ---

    void applyStrategyFill(int strategyId, int listingId, Side side, long qty, long price, long fee);

    Position getStrategyPosition(int strategyId, int listingId);

    // --- Leaves tracking (per-strategy only) ---

    void addStrategyLeaves(int strategyId, int listingId, Side side, long qty);

    void removeStrategyLeaves(int strategyId, int listingId, Side side, long qty);
}
