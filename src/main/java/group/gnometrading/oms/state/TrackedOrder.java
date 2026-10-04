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
    private boolean modifyPending;
    private long preModifyPrice;
    private long preModifySize;

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
        this.modifyPending = false;
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
        this.modifyPending = false;
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
                modifyPending = false;
            }
            case PARTIAL_FILL -> {
                state = OrderState.PARTIALLY_FILLED;
                filledQty = cumulativeQtyAfter(report);
            }
            case FILL -> {
                state = OrderState.FILLED;
                filledQty = cumulativeQtyAfter(report);
            }
            case CANCEL -> {
                state = OrderState.CANCELED;
            }
            case REJECT -> {
                state = OrderState.REJECTED;
            }
            case EXPIRE -> {
                state = OrderState.EXPIRED;
            }
            case CANCEL_REJECT -> {
                // While a modify is pending, the refusal is the modify's: the order keeps working as it was.
                if (modifyPending) {
                    price = preModifyPrice;
                    size = preModifySize;
                    modifyPending = false;
                }
            }
            case NULL_VAL -> {
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
        this.preModifyPrice = this.price;
        this.preModifySize = this.size;
        this.modifyPending = true;
        this.price = newPrice;
        this.size = newOrderQty;
    }

    /**
     * What the OMS has working on this order: its quantity, as last requested, less what has filled. While a modify
     * is pending either size could still fill, so the larger counts. Venue-reported leaves describe the order before
     * a pending modify, so position accounting uses this instead.
     */
    public long workingQty() {
        if (!active || state.isTerminal()) {
            return 0;
        }
        final long workingSize = modifyPending ? Math.max(preModifySize, size) : size;
        return Math.max(0, workingSize - filledQty);
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

    public boolean isActive() {
        return active;
    }
}
