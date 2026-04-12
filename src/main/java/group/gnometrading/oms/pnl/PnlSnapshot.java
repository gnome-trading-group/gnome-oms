package group.gnometrading.oms.pnl;

import group.gnometrading.oms.position.Position;

final class PnlSnapshot {
    int strategyId;
    int listingId;
    long netQuantity;
    long avgEntryPrice;
    long realizedPnl;
    long totalFees;
    long leavesBuyQty;
    long leavesSellQty;
    long markPrice;
    long unrealizedPnl;

    void set(int sid, int lid, Position position) {
        this.strategyId = sid;
        this.listingId = lid;
        this.netQuantity = position.netQuantity;
        this.avgEntryPrice = position.getAvgEntryPrice();
        this.realizedPnl = position.realizedPnl;
        this.totalFees = position.totalFees;
        this.leavesBuyQty = position.leavesBuyQty;
        this.leavesSellQty = position.leavesSellQty;
        this.markPrice = 0;
        this.unrealizedPnl = 0;
    }
}
