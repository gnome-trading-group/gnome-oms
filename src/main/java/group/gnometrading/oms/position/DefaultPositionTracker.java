package group.gnometrading.oms.position;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.schemas.Side;

public final class DefaultPositionTracker implements PositionTracker {

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int DEFAULT_POOL_CAPACITY = 50;

    // Firm-level positions: listingId -> Position
    private final IntHashMap<Position> positions;

    // Per-strategy positions: strategyId -> (listingId -> Position)
    private final IntHashMap<IntHashMap<Position>> strategyPositions;

    private final SingleThreadedObjectPool<Position> positionPool;
    private final SingleThreadedObjectPool<IntHashMap<Position>> strategyInnerMapPool;

    private final SharedPositionBuffer sharedBuffer;

    public DefaultPositionTracker() {
        this(null, DEFAULT_INITIAL_CAPACITY, DEFAULT_POOL_CAPACITY);
    }

    public DefaultPositionTracker(SharedPositionBuffer sharedBuffer) {
        this(sharedBuffer, DEFAULT_INITIAL_CAPACITY, DEFAULT_POOL_CAPACITY);
    }

    public DefaultPositionTracker(SharedPositionBuffer sharedBuffer, int initialCapacity, int poolCapacity) {
        this.sharedBuffer = sharedBuffer;
        this.positions = new IntHashMap<>(initialCapacity);
        this.strategyPositions = new IntHashMap<>(4);
        this.positionPool = new SingleThreadedObjectPool<>(Position::new, poolCapacity);
        this.strategyInnerMapPool = new SingleThreadedObjectPool<>(IntHashMap::new, poolCapacity);
    }

    /**
     * Pre-registers a (strategyId, listingId) pair and assigns it a slot in the shared buffer.
     * Must be called at startup before the OMS and strategy threads begin.
     *
     * @return the assigned slot index, for use in constructing {@link LivePositionView}
     */
    public int registerSlot(int strategyId, int listingId) {
        IntHashMap<Position> strategyMap = getOrCreateStrategyMap(strategyId);
        Position position = getOrCreatePosition(strategyMap, listingId);
        int slot = sharedBuffer.register();
        position.sharedSlot = slot;
        return slot;
    }

    // --- PositionView (read-only, firm-level) ---

    @Override
    public Position getPosition(int listingId) {
        return positions.get(listingId);
    }

    @Override
    public long getEffectiveQuantity(int listingId) {
        Position pos = positions.get(listingId);
        return pos != null ? pos.getEffectiveQuantity() : 0;
    }

    // --- PositionTracker (per-strategy, read+write) ---

    @Override
    public void applyStrategyFill(int strategyId, int listingId, Side side, long qty, long price, long fee) {
        Position firmPosition = getOrCreatePosition(positions, listingId);
        firmPosition.applyFill(side, qty, price, fee);

        IntHashMap<Position> strategyMap = getOrCreateStrategyMap(strategyId);
        Position stratPosition = getOrCreatePosition(strategyMap, listingId);
        stratPosition.applyFill(side, qty, price, fee);
        syncToSharedBuffer(stratPosition);
    }

    @Override
    public Position getStrategyPosition(int strategyId, int listingId) {
        IntHashMap<Position> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap == null) {
            return null;
        }
        return strategyMap.get(listingId);
    }

    @Override
    public void addStrategyLeaves(int strategyId, int listingId, Side side, long qty) {
        IntHashMap<Position> strategyMap = getOrCreateStrategyMap(strategyId);
        Position position = getOrCreatePosition(strategyMap, listingId);
        position.addLeaves(side, qty);
        syncToSharedBuffer(position);
    }

    @Override
    public void removeStrategyLeaves(int strategyId, int listingId, Side side, long qty) {
        Position position = getStrategyPosition(strategyId, listingId);
        if (position != null) {
            position.removeLeaves(side, qty);
            syncToSharedBuffer(position);
        }
    }

    // --- Internal helpers ---

    private void syncToSharedBuffer(Position position) {
        if (sharedBuffer != null && position.sharedSlot >= 0) {
            sharedBuffer.write(position.sharedSlot, position);
        }
    }

    private Position getOrCreatePosition(IntHashMap<Position> map, int listingId) {
        Position position = map.get(listingId);
        if (position == null) {
            position = positionPool.acquire().getItem();
            position.init(listingId);
            map.put(listingId, position);
        }
        return position;
    }

    private IntHashMap<Position> getOrCreateStrategyMap(int strategyId) {
        IntHashMap<Position> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap == null) {
            strategyMap = strategyInnerMapPool.acquire().getItem();
            strategyPositions.put(strategyId, strategyMap);
        }
        return strategyMap;
    }
}
