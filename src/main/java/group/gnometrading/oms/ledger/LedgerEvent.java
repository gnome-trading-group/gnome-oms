package group.gnometrading.oms.ledger;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.state.OrderState;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;

/** One ledger event, preallocated in the ring and overwritten in place. Fields a type doesn't use are left stale. */
public final class LedgerEvent {

    public static final int EXCHANGE_ORDER_ID_LENGTH = OrderExecutionReportDecoder.exchangeOrderIdLength();

    public LedgerEventType type;
    public int listingId;
    public int exchangeId;
    public long clientOidCounter;
    public Side side;
    public long price;
    public long size;
    public long eventTimeNs;

    public long cumQtyAfter;
    public long fee;
    public long netQuantityAfter;
    public long totalCostAfter;
    public long realizedPnlAfter;
    public long feesAfter;
    public long positionVersion;

    public Liquidity liquidity;
    public OrderState closeState;
    public RejectReason rejectReason;
    public long count;

    public final byte[] exchangeOrderId = new byte[EXCHANGE_ORDER_ID_LENGTH];
    public int exchangeOrderIdLength;

    void setPosition(final Position position) {
        this.netQuantityAfter = position.netQuantity;
        this.totalCostAfter = position.totalCost;
        this.realizedPnlAfter = position.realizedPnl;
        this.feesAfter = position.totalFees;
        this.positionVersion = position.version;
    }

    public void copyFrom(final LedgerEvent src) {
        this.type = src.type;
        this.listingId = src.listingId;
        this.exchangeId = src.exchangeId;
        this.clientOidCounter = src.clientOidCounter;
        this.side = src.side;
        this.price = src.price;
        this.size = src.size;
        this.eventTimeNs = src.eventTimeNs;
        this.cumQtyAfter = src.cumQtyAfter;
        this.fee = src.fee;
        this.netQuantityAfter = src.netQuantityAfter;
        this.totalCostAfter = src.totalCostAfter;
        this.realizedPnlAfter = src.realizedPnlAfter;
        this.feesAfter = src.feesAfter;
        this.positionVersion = src.positionVersion;
        this.liquidity = src.liquidity;
        this.closeState = src.closeState;
        this.rejectReason = src.rejectReason;
        this.count = src.count;
        this.exchangeOrderIdLength = src.exchangeOrderIdLength;
        System.arraycopy(src.exchangeOrderId, 0, this.exchangeOrderId, 0, src.exchangeOrderIdLength);
    }
}
