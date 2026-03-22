package group.gnometrading.oms.position;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.LongHashMap;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.schemas.Side;

import java.util.function.Consumer;

public class DefaultPositionTracker implements PositionTracker {

    private final IntHashMap<LongHashMap<Position>> positions;
    private final SingleThreadedObjectPool<Position> positionPool;
    private final SingleThreadedObjectPool<LongHashMap<Position>> innerMapPool;

    public DefaultPositionTracker() {
        this(16, 50);
    }

    public DefaultPositionTracker(int initialCapacity, int poolCapacity) {
        this.positions = new IntHashMap<>(initialCapacity);
        this.positionPool = new SingleThreadedObjectPool<>(Position::new, poolCapacity);
        this.innerMapPool = new SingleThreadedObjectPool<>(LongHashMap::new, poolCapacity);
    }

    @Override
    public void applyFill(int exchangeId, long securityId, Side side, long qty, long price, double fee) {
        Position position = getOrCreatePosition(exchangeId, securityId);
        position.applyFill(side, qty, price, fee);
    }

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
        for (int exchangeId : positions.keys()) {
            LongHashMap<Position> inner = positions.get(exchangeId);
            for (long securityId : inner.keys()) {
                consumer.accept(inner.get(securityId));
            }
        }
    }

    private Position getOrCreatePosition(int exchangeId, long securityId) {
        LongHashMap<Position> inner = positions.get(exchangeId);
        if (inner == null) {
            inner = innerMapPool.acquire().getItem();
            positions.put(exchangeId, inner);
        }

        Position position = inner.get(securityId);
        if (position == null) {
            position = positionPool.acquire().getItem();
            position.init(exchangeId, securityId);
            inner.put(securityId, position);
        }
        return position;
    }
}
