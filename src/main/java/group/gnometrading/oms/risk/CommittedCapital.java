package group.gnometrading.oms.risk;

import group.gnometrading.oms.position.Position;
import group.gnometrading.schemas.Statics;
import group.gnometrading.utils.ScaledMath;

/**
 * The most a strategy's position and working orders on one listing could cost it, in price units (1e9 per dollar).
 * The registry computes the same figure for its usage view ({@code committedCapital} in {@code ledger-math.ts}), so
 * the two must change together; {@code committed-capital-cases.json} in the test resources holds the shared cases.
 *
 * <p>A binary contract pays $1 or nothing, so a long can lose its cost and a short can lose $1 a unit less what it
 * took in. A working order that would only close part of the position commits nothing for that part: a buy against
 * a short, or a sell against a long, releases as much as it adds. Anything else is valued at its gross notional, as
 * though held at 1x leverage.
 */
public final class CommittedCapital {

    private CommittedCapital() {}

    /**
     * @param netQuantity the position, signed, in size units
     * @param totalCost what the position cost, in price units
     * @param leavesBuyQty working buy quantity
     * @param leavesBuyNotional working buy notional, each order at its own price
     * @param leavesSellQty working sell quantity
     * @param leavesSellNotional working sell notional, each order at its own price
     */
    public static long of(
            final boolean binary,
            final long netQuantity,
            final long totalCost,
            final long leavesBuyQty,
            final long leavesBuyNotional,
            final long leavesSellQty,
            final long leavesSellNotional) {
        if (!binary) {
            return add(add(Math.abs(totalCost), leavesBuyNotional), leavesSellNotional);
        }
        final long position;
        if (netQuantity > 0) {
            position = totalCost;
        } else if (netQuantity < 0) {
            position = Position.notional(Statics.PRICE_SCALING_FACTOR, -netQuantity) - totalCost;
        } else {
            position = 0;
        }
        final long buys = leavesBuyQty <= 0
                ? 0
                : ScaledMath.multiplyDivide(
                        leavesBuyNotional, Math.max(0, leavesBuyQty - Math.max(0, -netQuantity)), leavesBuyQty);
        final long sells = leavesSellQty <= 0
                ? 0
                : ScaledMath.multiplyDivide(
                        Position.notional(Statics.PRICE_SCALING_FACTOR, leavesSellQty) - leavesSellNotional,
                        Math.max(0, leavesSellQty - Math.max(0, netQuantity)),
                        leavesSellQty);
        return add(add(position, buys), sells);
    }

    /** The committed capital of one position, with its working orders. */
    public static long of(final boolean binary, final Position position) {
        return of(
                binary,
                position.netQuantity,
                position.totalCost,
                position.leavesBuyQty,
                position.leavesBuyNotional,
                position.leavesSellQty,
                position.leavesSellNotional);
    }

    // Saturates like ScaledMath, so a limit check on an absurd figure fails closed rather than wrapping negative.
    static long add(final long first, final long second) {
        final long sum = first + second;
        if (((first ^ sum) & (second ^ sum)) < 0) {
            return first < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }
}
