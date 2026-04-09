package group.gnometrading.oms.state;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.function.Consumer;

public final class RingBufferOrderStateManager implements OrderStateManager {

    private static final int DEFAULT_CAPACITY = 256;

    private final TrackedOrder[] slots;
    private final int mask;

    public RingBufferOrderStateManager() {
        this(DEFAULT_CAPACITY);
    }

    public RingBufferOrderStateManager(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("Capacity must be a positive power of two, got: " + capacity);
        }
        this.mask = capacity - 1;
        this.slots = new TrackedOrder[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new TrackedOrder();
        }
    }

    @Override
    public TrackedOrder trackOrder(Order order) {
        long counter = order.getClientOidCounter();
        int index = (int) (counter & mask);
        TrackedOrder slot = slots[index];
        if (slot.isActive()) {
            throw new IllegalStateException(
                    "Slot collision at index " + index + ": active counter=" + slot.getClientOidCounter()
                            + ", new counter=" + counter + ". Increase capacity (currently " + slots.length + ").");
        }
        slot.init(order);
        return slot;
    }

    @Override
    public TrackedOrder applyExecutionReport(OrderExecutionReport report) {
        long counter = report.getClientOidCounter();
        int index = (int) (counter & mask);
        TrackedOrder slot = slots[index];
        if (!slot.isActive() || slot.getClientOidCounter() != counter) {
            return null;
        }
        slot.applyExecutionReport(report);
        return slot;
    }

    @Override
    public TrackedOrder getOrder(long clientOidCounter) {
        int index = (int) (clientOidCounter & mask);
        TrackedOrder slot = slots[index];
        return slot.isActive() && slot.getClientOidCounter() == clientOidCounter ? slot : null;
    }

    @Override
    public void releaseOrder(TrackedOrder order) {
        order.reset();
    }

    @Override
    public void forEachOrder(Consumer<TrackedOrder> consumer) {
        for (int i = 0; i <= mask; i++) {
            if (slots[i].isActive()) {
                consumer.accept(slots[i]);
            }
        }
    }
}
