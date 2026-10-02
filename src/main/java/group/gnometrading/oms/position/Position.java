package group.gnometrading.oms.position;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.utils.ScaledMath;

/**
 * Quantities are in size units (1e6 per unit); {@code totalCost}, {@code realizedPnl} and {@code totalFees} are money
 * in price units (1e9 per dollar).
 */
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
            totalCost = notional(price, qty);
        } else if (Long.signum(netQuantity) == Long.signum(signedQty)) {
            totalCost += notional(price, qty);
            netQuantity += signedQty;
        } else {
            long closeQty = Math.min(Math.abs(netQuantity), qty);
            long avgEntry = getAvgEntryPrice();

            if (netQuantity > 0) {
                realizedPnl += notional(price - avgEntry, closeQty);
            } else {
                realizedPnl += notional(avgEntry - price, closeQty);
            }

            long prevQty = netQuantity;
            netQuantity += signedQty;

            if (netQuantity == 0) {
                totalCost = 0;
            } else if (Long.signum(netQuantity) != Long.signum(prevQty)) {
                long remainder = Math.abs(netQuantity);
                totalCost = notional(price, remainder);
            } else {
                totalCost = notional(avgEntry, Math.abs(netQuantity));
            }
        }
    }

    public long getAvgEntryPrice() {
        return netQuantity == 0
                ? 0
                : ScaledMath.multiplyDivide(totalCost, Statics.SIZE_SCALING_FACTOR, Math.abs(netQuantity));
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

    /** Money in price units for {@code qty} units at {@code price}. */
    public static long notional(long price, long qty) {
        return ScaledMath.multiplyDivide(price, qty, Statics.SIZE_SCALING_FACTOR);
    }

    /** Confirmed net quantity + inflight buy - inflight sell. */
    public long getEffectiveQuantity() {
        return netQuantity + leavesBuyQty - leavesSellQty;
    }
}
