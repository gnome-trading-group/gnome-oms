package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

public final class Position {

    public int listingId;
    public long netQuantity;
    public long totalCost;
    public long realizedPnl;
    public long totalFees;
    public long leavesBuyQty;
    public long leavesSellQty;
    int sharedSlot = -1;

    public void init(int id) {
        this.listingId = id;
        this.netQuantity = 0;
        this.totalCost = 0;
        this.realizedPnl = 0;
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
            totalCost = price * qty;
        } else if (Long.signum(netQuantity) == Long.signum(signedQty)) {
            totalCost += price * qty;
            netQuantity += signedQty;
        } else {
            long closeQty = Math.min(Math.abs(netQuantity), qty);
            long avgEntry = getAvgEntryPrice();

            if (netQuantity > 0) {
                realizedPnl += closeQty * (price - avgEntry);
            } else {
                realizedPnl += closeQty * (avgEntry - price);
            }

            long prevQty = netQuantity;
            netQuantity += signedQty;

            if (netQuantity == 0) {
                totalCost = 0;
            } else if (Long.signum(netQuantity) != Long.signum(prevQty)) {
                long remainder = Math.abs(netQuantity);
                totalCost = price * remainder;
            } else {
                totalCost = avgEntry * Math.abs(netQuantity);
            }
        }
    }

    public long getAvgEntryPrice() {
        return netQuantity == 0 ? 0 : totalCost / Math.abs(netQuantity);
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

    void setFromBuffer(long netQty, long cost, long pnl, long fees, long leavesBuy, long leavesSell) {
        this.netQuantity = netQty;
        this.totalCost = cost;
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
