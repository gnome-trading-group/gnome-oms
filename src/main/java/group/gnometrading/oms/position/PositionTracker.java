package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

import java.util.function.Consumer;

public interface PositionTracker {

    // --- Firm-level (aggregate) ---

    Position getPosition(int exchangeId, long securityId);

    void forEachPosition(Consumer<Position> consumer);

    // --- Per-strategy ---

    void applyStrategyFill(int strategyId, int exchangeId, long securityId, Side side, long qty, long price, double fee);

    Position getStrategyPosition(int strategyId, int exchangeId, long securityId);

    void forEachStrategyPosition(int strategyId, Consumer<Position> consumer);

    // --- Leaves tracking (per-strategy only) ---

    void addStrategyLeaves(int strategyId, int exchangeId, long securityId, Side side, long qty);

    void removeStrategyLeaves(int strategyId, int exchangeId, long securityId, Side side, long qty);
}
