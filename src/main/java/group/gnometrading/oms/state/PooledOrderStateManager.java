package group.gnometrading.oms.state;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.function.Consumer;
import org.agrona.collections.Long2LongHashMap;

/**
 * Tracks live orders in a fixed pool of preallocated slots, found by client OID through a primitive map.
 *
 * <p>Capacity bounds how many orders are open at once, not how many have been sent: a long-lived order never blocks
 * the ones cycling around it. Nothing allocates after construction.
 */
public final class PooledOrderStateManager implements OrderStateManager {

    private static final int DEFAULT_CAPACITY = 256;
    private static final long NO_SLOT = -1;
    private static final float MAP_LOAD_FACTOR = 0.65f;

    private final TrackedOrder[] slots;
    private final int[] freeSlots;
    private int freeCount;
    private final Long2LongHashMap slotByCounter;

    public PooledOrderStateManager() {
        this(DEFAULT_CAPACITY);
    }

    public PooledOrderStateManager(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.slots = new TrackedOrder[capacity];
        this.freeSlots = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new TrackedOrder();
            // Lowest slot is handed out first.
            freeSlots[i] = capacity - 1 - i;
        }
        this.freeCount = capacity;
        // Sized so the map never resizes, which would allocate, at full occupancy.
        this.slotByCounter = new Long2LongHashMap(capacity * 2, MAP_LOAD_FACTOR, NO_SLOT);
    }

    @Override
    public boolean isFull() {
        return freeCount == 0;
    }

    @Override
    public TrackedOrder trackOrder(Order order) {
        long counter = order.getClientOidCounter();
        if (slotByCounter.get(counter) != NO_SLOT) {
            throw new IllegalStateException("Order " + counter + " is already tracked");
        }
        if (freeCount == 0) {
            throw new IllegalStateException("All " + slots.length + " order slots are in use; check isFull() first");
        }
        int index = freeSlots[--freeCount];
        slotByCounter.put(counter, index);
        TrackedOrder slot = slots[index];
        slot.init(order);
        return slot;
    }

    @Override
    public TrackedOrder applyExecutionReport(OrderExecutionReport report) {
        TrackedOrder slot = getOrder(report.getClientOidCounter());
        if (slot == null) {
            return null;
        }
        slot.applyExecutionReport(report);
        return slot;
    }

    @Override
    public TrackedOrder getOrder(long clientOidCounter) {
        long index = slotByCounter.get(clientOidCounter);
        return index == NO_SLOT ? null : slots[(int) index];
    }

    @Override
    public void releaseOrder(TrackedOrder order) {
        long index = slotByCounter.remove(order.getClientOidCounter());
        if (index == NO_SLOT) {
            return;
        }
        order.reset();
        freeSlots[freeCount++] = (int) index;
    }

    @Override
    public void forEachOrder(Consumer<TrackedOrder> consumer) {
        for (TrackedOrder slot : slots) {
            if (slot.isActive()) {
                consumer.accept(slot);
            }
        }
    }
}
