package group.gnometrading.oms.order;

import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

public final class OmsOrder {

    private int exchangeId;
    private long securityId;
    private int strategyId;
    private long clientOid;
    private Side side;
    private long price;
    private long size;
    private OrderType orderType;
    private TimeInForce timeInForce;

    public OmsOrder() {}

    @SuppressWarnings("checkstyle:HiddenField")
    public void set(
            int exchangeId,
            long securityId,
            int strategyId,
            long clientOid,
            Side side,
            long price,
            long size,
            OrderType orderType,
            TimeInForce timeInForce) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.strategyId = strategyId;
        this.clientOid = clientOid;
        this.side = side;
        this.price = price;
        this.size = size;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.strategyId = 0;
        this.clientOid = 0;
        this.side = null;
        this.price = 0;
        this.size = 0;
        this.orderType = null;
        this.timeInForce = null;
    }

    public int exchangeId() {
        return exchangeId;
    }

    public long securityId() {
        return securityId;
    }

    public int strategyId() {
        return strategyId;
    }

    public long clientOid() {
        return clientOid;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public long size() {
        return size;
    }

    public OrderType orderType() {
        return orderType;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }
}
