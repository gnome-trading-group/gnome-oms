package group.gnometrading.oms.state;

import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import java.util.function.Consumer;

public interface OrderStateManager {

    TrackedOrder trackOrder(OmsOrder order);

    TrackedOrder applyExecutionReport(OmsExecutionReport report);

    TrackedOrder getOrder(long clientOid);

    void releaseOrder(TrackedOrder order);

    void forEachOpenOrder(Consumer<TrackedOrder> consumer);

    void forEachOpenOrderFor(int exchangeId, long securityId, Consumer<TrackedOrder> consumer);

    void forEachOpenStrategyOrderFor(int strategyId, int exchangeId, long securityId, Consumer<TrackedOrder> consumer);

    void forEachOrder(Consumer<TrackedOrder> consumer);
}
