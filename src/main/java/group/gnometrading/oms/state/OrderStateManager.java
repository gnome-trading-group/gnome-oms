package group.gnometrading.oms.state;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.function.Consumer;

public interface OrderStateManager {

    TrackedOrder trackOrder(Order order);

    TrackedOrder applyExecutionReport(OrderExecutionReport report);

    TrackedOrder getOrder(long clientOidCounter);

    void releaseOrder(TrackedOrder order);

    void forEachOrder(Consumer<TrackedOrder> consumer);
}
