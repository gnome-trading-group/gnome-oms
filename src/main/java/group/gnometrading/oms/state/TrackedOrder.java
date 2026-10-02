package group.gnometrading.oms.state;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
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
    }

    /**
     * Applies a report the OMS has already validated: a fill carries its fill quantity and price, and moves the
     * cumulative quantity forward. Optional quantities a venue leaves out are derived from what is known.
     */
    public void applyExecutionReport(OrderExecutionReport report) {
        ExecType exec = report.decoder.execType();
        switch (exec) {
            case NEW -> {
                state = OrderState.NEW;
                long leaves = report.decoder.leavesQty();
                if (leaves != OrderExecutionReportDecoder.leavesQtyNullValue()) {
                    leavesQty = leaves;
                }
            }
            case PARTIAL_FILL -> {
                state = OrderState.PARTIALLY_FILLED;
                filledQty = cumulativeQtyAfter(report);
                long leaves = report.decoder.leavesQty();
                leavesQty = leaves != OrderExecutionReportDecoder.leavesQtyNullValue()
                        ? leaves
                        : Math.max(0, size - filledQty);
            }
            case FILL -> {
                state = OrderState.FILLED;
                filledQty = cumulativeQtyAfter(report);
                leavesQty = 0;
            }
            case CANCEL -> {
                state = OrderState.CANCELED;
                leavesQty = 0;
            }
            case REJECT -> {
                state = OrderState.REJECTED;
                leavesQty = 0;
            }
            case EXPIRE -> {
                state = OrderState.EXPIRED;
                leavesQty = 0;
            }
            case CANCEL_REJECT, NULL_VAL -> {
                /* no state change */
            }
        }
    }

    /** The cumulative filled quantity once {@code fill} is applied; a venue may report only the fill itself. */
    public long cumulativeQtyAfter(OrderExecutionReport fill) {
        long cumulative = fill.decoder.cumulativeQty();
        return cumulative != OrderExecutionReportDecoder.cumulativeQtyNullValue()
                ? cumulative
                : filledQty + fill.decoder.filledQty();
    }

    /** {@code newOrderQty} is the FIX order quantity, so what remains working is that less the fills. */
    public void modify(long newPrice, long newOrderQty) {
        this.price = newPrice;
        this.size = newOrderQty;
        this.leavesQty = Math.max(0, newOrderQty - filledQty);
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
}
