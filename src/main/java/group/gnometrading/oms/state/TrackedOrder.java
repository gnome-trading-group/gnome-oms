package group.gnometrading.oms.state;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

public final class TrackedOrder {

    // Copied from Order at init time
    private int exchangeId;
    private long securityId;
    private int strategyId;
    private long clientOidCounter;
    private Side side;
    private long price;
    private long size;
    private OrderType orderType;
    private TimeInForce timeInForce;

    // Order state
    private boolean active;
    private OrderState state;
    private long filledQty;
    private long leavesQty;
    private long totalCost;

    public TrackedOrder() {
        reset();
    }

    public void init(Order order) {
        this.active = true;
        this.exchangeId = order.decoder.exchangeId();
        this.securityId = order.decoder.securityId();
        this.clientOidCounter = order.getClientOidCounter();
        this.strategyId = order.getClientOidStrategyId();
        this.side = order.decoder.side();
        this.price = order.decoder.price();
        this.size = order.decoder.size();
        this.orderType = order.decoder.orderType();
        this.timeInForce = order.decoder.timeInForce();
        this.state = OrderState.PENDING_NEW;
        this.filledQty = 0;
        this.leavesQty = order.decoder.size();
        this.totalCost = 0;
    }

    public void reset() {
        this.active = false;
        this.exchangeId = 0;
        this.securityId = 0;
        this.strategyId = 0;
        this.clientOidCounter = 0;
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

    public void applyExecutionReport(OrderExecutionReport report) {
        ExecType exec = report.decoder.execType();
        switch (exec) {
            case NEW -> {
                state = OrderState.NEW;
                leavesQty = report.decoder.leavesQty();
            }
            case PARTIAL_FILL -> {
                state = OrderState.PARTIALLY_FILLED;
                totalCost += report.decoder.fillPrice() * report.decoder.filledQty() + report.decoder.fee();
                filledQty = report.decoder.cumulativeQty();
                leavesQty = report.decoder.leavesQty();
            }
            case FILL -> {
                state = OrderState.FILLED;
                totalCost += report.decoder.fillPrice() * report.decoder.filledQty() + report.decoder.fee();
                filledQty = report.decoder.cumulativeQty();
                leavesQty = 0;
            }
            case CANCEL -> state = OrderState.CANCELED;
            case REJECT -> state = OrderState.REJECTED;
            case EXPIRE -> state = OrderState.EXPIRED;
            case CANCEL_REJECT, NULL_VAL -> {
                /* no state change */
            }
        }
    }

    public void modify(long newPrice, long newSize) {
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

    public long getClientOidCounter() {
        return clientOidCounter;
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

    public boolean isActive() {
        return active;
    }

    public long getAvgFillPrice() {
        return filledQty == 0 ? 0 : totalCost / filledQty;
    }
}
