package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

public final class Position {

    public int listingId;
    public long netQuantity;
    public long avgEntryPrice;
    public double realizedPnl;
    public long totalFees;
    public long leavesBuyQty;
    public long leavesSellQty;
    int sharedSlot = -1;

    public void init(int id) {
        this.listingId = id;
        this.netQuantity = 0;
        this.avgEntryPrice = 0;
        this.realizedPnl = 0.0;
        this.totalFees = 0;
        this.leavesBuyQty = 0;
        this.leavesSellQty = 0;
        this.sharedSlot = -1;
    }

    public void applyFill(Side side, long qty, long price, long fee) {
        long signedQty = (side == Side.Bid) ? qty : -qty;
        totalFees += fee;

        if (netQuantity == 0) {
            netQuantity = signedQty;
            avgEntryPrice = price;
        } else if (Long.signum(netQuantity) == Long.signum(signedQty)) {
            long totalCost = avgEntryPrice * Math.abs(netQuantity) + price * qty;
            netQuantity += signedQty;
            avgEntryPrice = totalCost / Math.abs(netQuantity);
        } else {
            long prevQty = netQuantity;
            long closeQty = Math.min(Math.abs(netQuantity), qty);

            if (netQuantity > 0) {
                realizedPnl += closeQty * (price - avgEntryPrice);
            } else {
                realizedPnl += closeQty * (avgEntryPrice - price);
            }

            netQuantity += signedQty;
            if (netQuantity == 0) {
                avgEntryPrice = 0;
            } else if (Long.signum(netQuantity) != Long.signum(prevQty)) {
                avgEntryPrice = price;
            }
        }
    }

    public void addLeaves(Side side, long qty) {
        if (side == Side.Bid) {
            leavesBuyQty += qty;
        } else {
            leavesSellQty += qty;
        }
    }

    public void removeLeaves(Side side, long qty) {
        if (side == Side.Bid) {
            leavesBuyQty = Math.max(0, leavesBuyQty - qty);
        } else {
            leavesSellQty = Math.max(0, leavesSellQty - qty);
        }
    }

    void setFromBuffer(long netQty, long avgEntry, double pnl, long fees, long leavesBuy, long leavesSell) {
        this.netQuantity = netQty;
        this.avgEntryPrice = avgEntry;
        this.realizedPnl = pnl;
        this.totalFees = fees;
        this.leavesBuyQty = leavesBuy;
        this.leavesSellQty = leavesSell;
    }

    /** Confirmed net quantity + inflight buy - inflight sell. */
    public long getEffectiveQuantity() {
        return netQuantity + leavesBuyQty - leavesSellQty;
    }
}
