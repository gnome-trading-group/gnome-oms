package group.gnometrading.oms.order;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;

public final class OmsExecutionReport {

    private long clientOid;
    private int strategyId;
    private ExecType execType;
    private OrderStatus orderStatus;
    private long filledQty;
    private long fillPrice;
    private long totalFilledQty;
    private long leavesQty;
    private double fee;
    private int exchangeId;
    private long securityId;
    private long timestampEvent;
    private long timestampRecv;

    public OmsExecutionReport() {}

    @SuppressWarnings("checkstyle:HiddenField")
    public void set(
            long clientOid,
            int strategyId,
            ExecType execType,
            OrderStatus orderStatus,
            long filledQty,
            long fillPrice,
            long totalFilledQty,
            long leavesQty,
            double fee,
            int exchangeId,
            long securityId,
            long timestampEvent,
            long timestampRecv) {
        this.clientOid = clientOid;
        this.strategyId = strategyId;
        this.execType = execType;
        this.orderStatus = orderStatus;
        this.filledQty = filledQty;
        this.fillPrice = fillPrice;
        this.totalFilledQty = totalFilledQty;
        this.leavesQty = leavesQty;
        this.fee = fee;
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.timestampEvent = timestampEvent;
        this.timestampRecv = timestampRecv;
    }

    public void reset() {
        this.clientOid = 0;
        this.strategyId = 0;
        this.execType = null;
        this.orderStatus = null;
        this.filledQty = 0;
        this.fillPrice = 0;
        this.totalFilledQty = 0;
        this.leavesQty = 0;
        this.fee = 0.0;
        this.exchangeId = 0;
        this.securityId = 0;
        this.timestampEvent = 0;
        this.timestampRecv = 0;
    }

    public long clientOid() {
        return clientOid;
    }

    public int strategyId() {
        return strategyId;
    }

    public ExecType execType() {
        return execType;
    }

    public OrderStatus orderStatus() {
        return orderStatus;
    }

    public long filledQty() {
        return filledQty;
    }

    public long fillPrice() {
        return fillPrice;
    }

    public long totalFilledQty() {
        return totalFilledQty;
    }

    public long leavesQty() {
        return leavesQty;
    }

    public double fee() {
        return fee;
    }

    public int exchangeId() {
        return exchangeId;
    }

    public long securityId() {
        return securityId;
    }

    public long timestampEvent() {
        return timestampEvent;
    }

    public long timestampRecv() {
        return timestampRecv;
    }
}
