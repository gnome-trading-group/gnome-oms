package group.gnometrading.oms.state;

import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Side;

public class TrackedOrder {

    private OmsOrder originalOrder;
    private OrderState state;
    private long cumulativeQty;
    private long leavesQty;
    private long totalCost;

    public TrackedOrder() {
        reset();
    }

    public void init(OmsOrder order) {
        this.originalOrder = order;
        this.state = OrderState.PENDING_NEW;
        this.cumulativeQty = 0;
        this.leavesQty = order.size();
        this.totalCost = 0;
    }

    public void reset() {
        this.originalOrder = null;
        this.state = OrderState.PENDING_NEW;
        this.cumulativeQty = 0;
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
                cumulativeQty = report.cumulativeQty();
                leavesQty = report.leavesQty();
            }
            case FILL -> {
                state = OrderState.FILLED;
                totalCost += report.fillPrice() * report.filledQty();
                cumulativeQty = report.cumulativeQty();
                leavesQty = 0;
            }
            case CANCEL -> state = OrderState.CANCELED;
            case REJECT -> state = OrderState.REJECTED;
            case EXPIRE -> state = OrderState.EXPIRED;
            default -> { /* CANCEL_REJECT: no state change */ }
        }
    }

    public OmsOrder getOriginalOrder() {
        return originalOrder;
    }

    public OrderState getState() {
        return state;
    }

    public Side getSide() {
        return originalOrder.side();
    }

    public long getCumulativeQty() {
        return cumulativeQty;
    }

    public long getLeavesQty() {
        return leavesQty;
    }

    public long getAvgFillPrice() {
        return cumulativeQty == 0 ? 0 : totalCost / cumulativeQty;
    }

    public String getClientOid() {
        return originalOrder.clientOid();
    }

    public int getExchangeId() {
        return originalOrder.exchangeId();
    }

    public long getSecurityId() {
        return originalOrder.securityId();
    }
}
