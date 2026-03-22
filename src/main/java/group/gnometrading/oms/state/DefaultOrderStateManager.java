package group.gnometrading.oms.state;

import group.gnometrading.collections.PooledHashMap;
import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.pools.SingleThreadedObjectPool;

import java.util.function.Consumer;

public class DefaultOrderStateManager implements OrderStateManager {

    private final PooledHashMap<String, TrackedOrder> orders;
    private final SingleThreadedObjectPool<TrackedOrder> orderPool;

    public DefaultOrderStateManager() {
        this(128, 100);
    }

    public DefaultOrderStateManager(int initialCapacity, int poolCapacity) {
        this.orders = new PooledHashMap<>(initialCapacity);
        this.orderPool = new SingleThreadedObjectPool<>(TrackedOrder::new, poolCapacity);
    }

    @Override
    public TrackedOrder trackOrder(OmsOrder order) {
        TrackedOrder tracked = orderPool.acquire().getItem();
        tracked.init(order);
        orders.put(order.clientOid(), tracked);
        return tracked;
    }

    @Override
    public TrackedOrder applyExecutionReport(OmsExecutionReport report) {
        TrackedOrder tracked = orders.get(report.clientOid());
        if (tracked != null) {
            tracked.applyExecutionReport(report);
        }
        return tracked;
    }

    @Override
    public TrackedOrder getOrder(String clientOid) {
        return orders.get(clientOid);
    }

    @Override
    public void forEachOpenOrder(Consumer<TrackedOrder> consumer) {
        for (String clientOid : orders.keys()) {
            TrackedOrder order = orders.get(clientOid);
            if (!order.getState().isTerminal()) {
                consumer.accept(order);
            }
        }
    }

    @Override
    public void forEachOpenOrderFor(int exchangeId, long securityId, Consumer<TrackedOrder> consumer) {
        for (String clientOid : orders.keys()) {
            TrackedOrder order = orders.get(clientOid);
            if (!order.getState().isTerminal()
                    && order.getExchangeId() == exchangeId
                    && order.getSecurityId() == securityId) {
                consumer.accept(order);
            }
        }
    }

    @Override
    public void forEachOrder(Consumer<TrackedOrder> consumer) {
        for (String clientOid : orders.keys()) {
            consumer.accept(orders.get(clientOid));
        }
    }
}
