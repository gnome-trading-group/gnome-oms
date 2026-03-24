package group.gnometrading.oms.position;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.LongHashMap;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.schemas.Side;
import java.util.function.Consumer;

public final class DefaultPositionTracker implements PositionTracker {

    // Firm-level positions: (exchangeId, securityId) -> Position
    private final IntHashMap<LongHashMap<Position>> positions;

    // Per-strategy positions: (strategyId, exchangeId, securityId) -> Position
    private final IntHashMap<IntHashMap<LongHashMap<Position>>> strategyPositions;

    private final SingleThreadedObjectPool<Position> positionPool;
    private final SingleThreadedObjectPool<LongHashMap<Position>> innerMapPool;
    private final SingleThreadedObjectPool<IntHashMap<LongHashMap<Position>>> strategyInnerMapPool;

    public DefaultPositionTracker() {
        this(16, 50);
    }

    public DefaultPositionTracker(int initialCapacity, int poolCapacity) {
        this.positions = new IntHashMap<>(initialCapacity);
        this.strategyPositions = new IntHashMap<>(4);
        this.positionPool = new SingleThreadedObjectPool<>(Position::new, poolCapacity);
        this.innerMapPool = new SingleThreadedObjectPool<>(LongHashMap::new, poolCapacity);
        this.strategyInnerMapPool = new SingleThreadedObjectPool<>(IntHashMap::new, poolCapacity);
    }

    // --- Firm-level (aggregate) ---

    @Override
    public Position getPosition(int exchangeId, long securityId) {
        LongHashMap<Position> inner = positions.get(exchangeId);
        if (inner == null) {
            return null;
        }
        return inner.get(securityId);
    }

    @Override
    public void forEachPosition(Consumer<Position> consumer) {
        forEachInMap(positions, consumer);
    }

    // --- Per-strategy ---

    @Override
    public void applyStrategyFill(
            int strategyId, int exchangeId, long securityId, Side side, long qty, long price, double fee) {
        // Update firm-level
        Position firmPosition = getOrCreatePosition(positions, exchangeId, securityId);
        firmPosition.applyFill(side, qty, price, fee);

        // Update per-strategy
        IntHashMap<LongHashMap<Position>> strategyMap = getOrCreateStrategyMap(strategyId);
        Position stratPosition = getOrCreatePosition(strategyMap, exchangeId, securityId);
        stratPosition.applyFill(side, qty, price, fee);
    }

    @Override
    public Position getStrategyPosition(int strategyId, int exchangeId, long securityId) {
        IntHashMap<LongHashMap<Position>> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap == null) {
            return null;
        }
        LongHashMap<Position> inner = strategyMap.get(exchangeId);
        if (inner == null) {
            return null;
        }
        return inner.get(securityId);
    }

    @Override
    public void forEachStrategyPosition(int strategyId, Consumer<Position> consumer) {
        IntHashMap<LongHashMap<Position>> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap != null) {
            forEachInMap(strategyMap, consumer);
        }
    }

    // --- Inflight tracking (delegates to per-strategy Position) ---

    @Override
    public void addStrategyLeaves(int strategyId, int exchangeId, long securityId, Side side, long qty) {
        IntHashMap<LongHashMap<Position>> strategyMap = getOrCreateStrategyMap(strategyId);
        Position position = getOrCreatePosition(strategyMap, exchangeId, securityId);
        position.addLeaves(side, qty);
    }

    @Override
    public void removeStrategyLeaves(int strategyId, int exchangeId, long securityId, Side side, long qty) {
        Position position = getStrategyPosition(strategyId, exchangeId, securityId);
        if (position != null) {
            position.removeLeaves(side, qty);
        }
    }

    // --- Internal helpers ---

    private Position getOrCreatePosition(IntHashMap<LongHashMap<Position>> map, int exchangeId, long securityId) {
        LongHashMap<Position> inner = map.get(exchangeId);
        if (inner == null) {
            inner = innerMapPool.acquire().getItem();
            map.put(exchangeId, inner);
        }

        Position position = inner.get(securityId);
        if (position == null) {
            position = positionPool.acquire().getItem();
            position.init(exchangeId, securityId);
            inner.put(securityId, position);
        }
        return position;
    }

    private IntHashMap<LongHashMap<Position>> getOrCreateStrategyMap(int strategyId) {
        IntHashMap<LongHashMap<Position>> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap == null) {
            strategyMap = strategyInnerMapPool.acquire().getItem();
            strategyPositions.put(strategyId, strategyMap);
        }
        return strategyMap;
    }

    private void forEachInMap(IntHashMap<LongHashMap<Position>> map, Consumer<Position> consumer) {
        for (int exchangeId : map.keys()) {
            LongHashMap<Position> inner = map.get(exchangeId);
            for (long securityId : inner.keys()) {
                consumer.accept(inner.get(securityId));
            }
        }
    }
}
