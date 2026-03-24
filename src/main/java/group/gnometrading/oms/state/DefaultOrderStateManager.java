package group.gnometrading.oms.state;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.pools.PoolNode;
import group.gnometrading.pools.SingleThreadedObjectPool;

import java.util.function.Consumer;

public class DefaultOrderStateManager implements OrderStateManager {

    private final LongHashMap<TrackedOrder> orders;
    private final LongHashMap<PoolNode<TrackedOrder>> orderNodes;
    private final SingleThreadedObjectPool<TrackedOrder> orderPool;

    public DefaultOrderStateManager() {
        this(128, 100);
    }

    public DefaultOrderStateManager(int initialCapacity, int poolCapacity) {
        this.orders = new LongHashMap<>(initialCapacity);
        this.orderNodes = new LongHashMap<>(initialCapacity);
        this.orderPool = new SingleThreadedObjectPool<>(TrackedOrder::new, poolCapacity);
    }

    @Override
    public TrackedOrder trackOrder(OmsOrder order) {
        PoolNode<TrackedOrder> node = orderPool.acquire();
        TrackedOrder tracked = node.getItem();
        tracked.init(order);
        orders.put(order.clientOid(), tracked);
        orderNodes.put(order.clientOid(), node);
        return tracked;
    }

    @Override
    public TrackedOrder applyExecutionReport(OmsExecutionReport report) {
        TrackedOrder tracked = orders.get(report.clientOid());
        if (tracked != null) {
            tracked.applyExecutionReport(report);
            if (tracked.getState().isTerminal()) {
                orders.remove(report.clientOid());
            }
        }
        return tracked;
    }

    @Override
    public TrackedOrder getOrder(long clientOid) {
        return orders.get(clientOid);
    }

    @Override
    public void releaseOrder(TrackedOrder order) {
        long clientOid = order.getClientOid();
        PoolNode<TrackedOrder> node = orderNodes.remove(clientOid);
        if (node != null) {
            order.reset();
            orderPool.release(node);
        }
    }

    @Override
    public void forEachOpenOrder(Consumer<TrackedOrder> consumer) {
        for (long clientOid : orders.keys()) {
            TrackedOrder order = orders.get(clientOid);
            if (!order.getState().isTerminal()) {
                consumer.accept(order);
            }
        }
    }

    @Override
    public void forEachOpenOrderFor(int exchangeId, long securityId, Consumer<TrackedOrder> consumer) {
        for (long clientOid : orders.keys()) {
            TrackedOrder order = orders.get(clientOid);
            if (!order.getState().isTerminal()
                    && order.getExchangeId() == exchangeId
                    && order.getSecurityId() == securityId) {
                consumer.accept(order);
            }
        }
    }

    @Override
    public void forEachOpenStrategyOrderFor(int strategyId, int exchangeId, long securityId, Consumer<TrackedOrder> consumer) {
        for (long clientOid : orders.keys()) {
            TrackedOrder order = orders.get(clientOid);
            if (!order.getState().isTerminal()
                    && order.getExchangeId() == exchangeId
                    && order.getSecurityId() == securityId
                    && order.getStrategyId() == strategyId) {
                consumer.accept(order);
            }
        }
    }

    @Override
    public void forEachOrder(Consumer<TrackedOrder> consumer) {
        for (long clientOid : orders.keys()) {
            consumer.accept(orders.get(clientOid));
        }
    }
}
