package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

import java.util.function.Consumer;

public interface PositionTracker {

    void applyFill(int exchangeId, long securityId, Side side, long qty, long price, double fee);

    Position getPosition(int exchangeId, long securityId);

    void forEachPosition(Consumer<Position> consumer);
}
