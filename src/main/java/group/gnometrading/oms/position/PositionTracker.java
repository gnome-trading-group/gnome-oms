package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public interface PositionTracker {

    Position getPosition(int listingId);

    void applyStrategyFill(int strategyId, int listingId, Side side, long qty, long price, long fee);

    Position getStrategyPosition(int strategyId, int listingId);

    void addStrategyLeaves(int strategyId, int listingId, Side side, long qty);

    void removeStrategyLeaves(int strategyId, int listingId, Side side, long qty);

    void addOpenOrder(int strategyId, int listingId);

    void removeOpenOrder(int strategyId, int listingId);

    void forEachPosition(Consumer<Position> consumer);

    void forEachStrategyPosition(StrategyPositionConsumer consumer);

    /** Visits each of a strategy's live positions, one per listing. OMS thread only. */
    void forEachListingPosition(int strategyId, Consumer<Position> consumer);

    /** Visits each strategy that has a position. OMS thread only. */
    void forEachStrategyId(IntConsumer consumer);

    PositionView createPositionView(int strategyId);
}
