package group.gnometrading.oms.state;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.function.Consumer;

public interface OrderStateManager {

    /** True when no more orders can be tracked until one is released. */
    boolean isFull();

    TrackedOrder trackOrder(Order order);

    TrackedOrder applyExecutionReport(OrderExecutionReport report);

    TrackedOrder getOrder(long clientOidCounter);

    void releaseOrder(TrackedOrder order);

    void forEachOrder(Consumer<TrackedOrder> consumer);
}
