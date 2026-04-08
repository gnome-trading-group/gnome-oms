package group.gnometrading.oms.state;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.pools.PoolNode;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.schemas.ClientOidCodec;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.function.Consumer;

public final class DefaultOrderStateManager implements OrderStateManager {

    private static final int DEFAULT_INITIAL_CAPACITY = 128;
    private static final int DEFAULT_POOL_CAPACITY = 100;

    private final LongHashMap<TrackedOrder> orders;
    private final LongHashMap<PoolNode<TrackedOrder>> orderNodes;
    private final SingleThreadedObjectPool<TrackedOrder> orderPool;

    public DefaultOrderStateManager() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_POOL_CAPACITY);
    }

    public DefaultOrderStateManager(int initialCapacity, int poolCapacity) {
        this.orders = new LongHashMap<>(initialCapacity);
        this.orderNodes = new LongHashMap<>(initialCapacity);
        this.orderPool = new SingleThreadedObjectPool<>(TrackedOrder::new, poolCapacity);
    }

    @Override
    public TrackedOrder trackOrder(Order order) {
        PoolNode<TrackedOrder> node = orderPool.acquire();
        TrackedOrder tracked = node.getItem();
        tracked.init(order);
        long counter = tracked.getClientOidCounter();
        orders.put(counter, tracked);
        orderNodes.put(counter, node);
        return tracked;
    }

    @Override
    public TrackedOrder applyExecutionReport(OrderExecutionReport report) {
        long counter = ClientOidCodec.decodeCounter(report.buffer, report.decoder.clientOidEncodingOffset());
        TrackedOrder tracked = orders.get(counter);
        if (tracked != null) {
            tracked.applyExecutionReport(report);
            if (tracked.getState().isTerminal()) {
                orders.remove(counter);
            }
        }
        return tracked;
    }

    @Override
    public TrackedOrder getOrder(long clientOidCounter) {
        return orders.get(clientOidCounter);
    }

    @Override
    public void forEachOrder(final Consumer<TrackedOrder> consumer) {
        for (final Long key : orders.keys()) {
            final TrackedOrder tracked = orders.get(key);
            if (tracked != null) {
                consumer.accept(tracked);
            }
        }
    }

    @Override
    public void releaseOrder(TrackedOrder order) {
        long counter = order.getClientOidCounter();
        PoolNode<TrackedOrder> node = orderNodes.remove(counter);
        if (node != null) {
            order.reset();
            orderPool.release(node);
        }
    }
}
