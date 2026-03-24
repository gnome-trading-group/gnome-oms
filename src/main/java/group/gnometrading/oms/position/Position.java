package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

public final class Position {

    private int exchangeId;
    private long securityId;
    private long netQuantity;
    private long avgEntryPrice;
    private double realizedPnl;
    private double totalFees;
    private long leavesBuyQty;
    private long leavesSellQty;

    public Position() {
        reset();
    }

    public void init(int newExchangeId, long newSecurityId) {
        this.exchangeId = newExchangeId;
        this.securityId = newSecurityId;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.netQuantity = 0;
        this.avgEntryPrice = 0;
        this.realizedPnl = 0.0;
        this.totalFees = 0.0;
        this.leavesBuyQty = 0;
        this.leavesSellQty = 0;
    }

    public void applyFill(Side side, long qty, long price, double fee) {
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

    /** Confirmed net quantity + inflight buy - inflight sell. */
    public long getEffectiveQuantity() {
        return netQuantity + leavesBuyQty - leavesSellQty;
    }

    public int getExchangeId() {
        return exchangeId;
    }

    public long getSecurityId() {
        return securityId;
    }

    public long getNetQuantity() {
        return netQuantity;
    }

    public long getAvgEntryPrice() {
        return avgEntryPrice;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public double getTotalFees() {
        return totalFees;
    }

    public long getLeavesBuyQty() {
        return leavesBuyQty;
    }

    public long getLeavesSellQty() {
        return leavesSellQty;
    }
}
