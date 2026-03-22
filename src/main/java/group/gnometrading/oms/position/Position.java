package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;

public class Position {

    private int exchangeId;
    private long securityId;
    private long netQuantity;
    private long avgEntryPrice;
    private double realizedPnl;
    private double totalFees;

    public Position() {
        reset();
    }

    public void init(int exchangeId, long securityId) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.netQuantity = 0;
        this.avgEntryPrice = 0;
        this.realizedPnl = 0.0;
        this.totalFees = 0.0;
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
}
