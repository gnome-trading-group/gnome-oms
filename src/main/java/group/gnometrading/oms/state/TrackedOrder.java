package group.gnometrading.oms.state;

import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

public final class TrackedOrder {

    // Copied from OmsOrder at init time
    private int exchangeId;
    private long securityId;
    private int strategyId;
    private long clientOid;
    private Side side;
    private long price;
    private long size;
    private OrderType orderType;
    private TimeInForce timeInForce;

    // Order state
    private OrderState state;
    private long filledQty;
    private long leavesQty;
    private long totalCost;

    public TrackedOrder() {
        reset();
    }

    public void init(OmsOrder order) {
        this.exchangeId = order.exchangeId();
        this.securityId = order.securityId();
        this.strategyId = order.strategyId();
        this.clientOid = order.clientOid();
        this.side = order.side();
        this.price = order.price();
        this.size = order.size();
        this.orderType = order.orderType();
        this.timeInForce = order.timeInForce();
        this.state = OrderState.PENDING_NEW;
        this.filledQty = 0;
        this.leavesQty = order.size();
        this.totalCost = 0;
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
        this.state = OrderState.PENDING_NEW;
        this.filledQty = 0;
        this.leavesQty = 0;
        this.totalCost = 0;
    }

    public void applyExecutionReport(OmsExecutionReport report) {
        ExecType exec = report.execType();
        switch (exec) {
            case NEW -> {
                state = OrderState.NEW;
                leavesQty = report.leavesQty();
            }
            case PARTIAL_FILL -> {
                state = OrderState.PARTIALLY_FILLED;
                totalCost += report.fillPrice() * report.filledQty();
                filledQty = report.totalFilledQty();
                leavesQty = report.leavesQty();
            }
            case FILL -> {
                state = OrderState.FILLED;
                totalCost += report.fillPrice() * report.filledQty();
                filledQty = report.totalFilledQty();
                leavesQty = 0;
            }
            case CANCEL -> state = OrderState.CANCELED;
            case REJECT -> state = OrderState.REJECTED;
            case EXPIRE -> state = OrderState.EXPIRED;
            default -> {
                /* CANCEL_REJECT: no state change */
            }
        }
    }

    public void amend(long newPrice, long newSize) {
        this.price = newPrice;
        this.size = newSize;
        this.leavesQty = newSize;
    }

    public OrderState getState() {
        return state;
    }

    public int getExchangeId() {
        return exchangeId;
    }

    public long getSecurityId() {
        return securityId;
    }

    public int getStrategyId() {
        return strategyId;
    }

    public long getClientOid() {
        return clientOid;
    }

    public Side getSide() {
        return side;
    }

    public long getPrice() {
        return price;
    }

    public long getSize() {
        return size;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public long getFilledQty() {
        return filledQty;
    }

    public long getLeavesQty() {
        return leavesQty;
    }

    public long getAvgFillPrice() {
        return filledQty == 0 ? 0 : totalCost / filledQty;
    }
}
