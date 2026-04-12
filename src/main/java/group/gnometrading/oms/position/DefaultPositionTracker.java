package group.gnometrading.oms.position;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.schemas.Side;
import java.util.function.Consumer;

public final class DefaultPositionTracker implements PositionTracker {

    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    // Firm-level positions: listingId -> Position
    private final IntHashMap<Position> positions;

    // Per-strategy positions: strategyId -> (listingId -> Position)
    private final IntHashMap<IntHashMap<Position>> strategyPositions;

    private final SharedPositionBuffer sharedBuffer;
    private final SlotRegistry slotRegistry;
    private final Position iterFlyweight = new Position();

    public DefaultPositionTracker(SharedPositionBuffer sharedBuffer) {
        this(sharedBuffer, DEFAULT_INITIAL_CAPACITY);
    }

    public DefaultPositionTracker(SharedPositionBuffer sharedBuffer, int initialCapacity) {
        this.sharedBuffer = sharedBuffer;
        this.slotRegistry = new SlotRegistry(sharedBuffer.capacity());
        this.positions = new IntHashMap<>(initialCapacity);
        this.strategyPositions = new IntHashMap<>(4);
    }

    /**
     * Pre-registers a (strategyId, listingId) pair and assigns it a slot in the shared buffer.
     * Must be called at startup before the OMS and strategy threads begin.
     */
    public void registerSlot(int strategyId, int listingId) {
        IntHashMap<Position> strategyMap = getOrCreateStrategyMap(strategyId);
        Position position = getOrCreatePosition(strategyMap, listingId);
        int slot = sharedBuffer.register();
        position.sharedSlot = slot;
        slotRegistry.register(slot, strategyId, listingId);
    }

    @Override
    public Position getPosition(int listingId) {
        return positions.get(listingId);
    }

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
        getOrCreatePosition(positions, listingId).addLeaves(side, qty);

        IntHashMap<Position> strategyMap = getOrCreateStrategyMap(strategyId);
        Position stratPosition = getOrCreatePosition(strategyMap, listingId);
        stratPosition.addLeaves(side, qty);
        syncToSharedBuffer(stratPosition);
    }

    @Override
    public void removeStrategyLeaves(int strategyId, int listingId, Side side, long qty) {
        Position firmPosition = positions.get(listingId);
        if (firmPosition != null) {
            firmPosition.removeLeaves(side, qty);
        }

        Position stratPosition = getStrategyPosition(strategyId, listingId);
        if (stratPosition != null) {
            stratPosition.removeLeaves(side, qty);
            syncToSharedBuffer(stratPosition);
        }
    }

    @Override
    public void forEachPosition(Consumer<Position> consumer) {
        positions.forEachValue(consumer);
    }

    @Override
    public void forEachStrategyPosition(StrategyPositionConsumer consumer) {
        for (int slot = 0; slot < slotRegistry.count(); slot++) {
            sharedBuffer.readSpinning(slot, iterFlyweight);
            consumer.accept(slotRegistry.strategyId(slot), slotRegistry.listingId(slot), iterFlyweight);
        }
    }

    @Override
    public PositionView createPositionView(int strategyId) {
        IntToIntHashMap slotByListingId = new IntToIntHashMap();
        for (int slot = 0; slot < slotRegistry.count(); slot++) {
            if (slotRegistry.strategyId(slot) == strategyId) {
                slotByListingId.put(slotRegistry.listingId(slot), slot);
            }
        }
        return new LivePositionView(sharedBuffer, slotByListingId);
    }

    private void syncToSharedBuffer(Position position) {
        if (sharedBuffer != null && position.sharedSlot >= 0) {
            sharedBuffer.write(position.sharedSlot, position);
        }
    }

    private Position getOrCreatePosition(IntHashMap<Position> map, int listingId) {
        Position position = map.get(listingId);
        if (position == null) {
            position = new Position();
            position.init(listingId);
            map.put(listingId, position);
        }
        return position;
    }

    private IntHashMap<Position> getOrCreateStrategyMap(int strategyId) {
        IntHashMap<Position> strategyMap = strategyPositions.get(strategyId);
        if (strategyMap == null) {
            strategyMap = new IntHashMap<>();
            strategyPositions.put(strategyId, strategyMap);
        }
        return strategyMap;
    }
}
