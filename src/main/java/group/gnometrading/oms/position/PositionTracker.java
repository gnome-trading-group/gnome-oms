package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;
import java.util.function.Consumer;

public interface PositionTracker {

    Position getPosition(int listingId);

    void applyStrategyFill(int strategyId, int listingId, Side side, long qty, long price, long fee);

    Position getStrategyPosition(int strategyId, int listingId);

    void addStrategyLeaves(int strategyId, int listingId, Side side, long qty);

    void removeStrategyLeaves(int strategyId, int listingId, Side side, long qty);

    void forEachPosition(Consumer<Position> consumer);

    void forEachStrategyPosition(StrategyPositionConsumer consumer);

    PositionView createPositionView(int strategyId);
}
