package group.gnometrading.oms.ledger;

import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.state.OrderState;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;

/**
 * Where the OMS records what must outlive the process: the orders it sends and the fills it books. OMS thread only,
 * and allocation-free. Recording never blocks or fails the caller; a sink that can't keep up says so through
 * {@link #isFailing}, and the OMS halts trading.
 */
public interface LedgerSink {

    /** Records nothing, for runs with no ledger: backtests and local runs without a session. */
    LedgerSink NONE = new LedgerSink() {
        @Override
        public void orderOpened(
                int strategyId,
                int listingId,
                int exchangeId,
                long clientOidCounter,
                Side side,
                long price,
                long size,
                long timeNs) {}

        @Override
        public void orderAcked(
                int strategyId, int listingId, int exchangeId, long clientOidCounter, OrderExecutionReport report) {}

        @Override
        public void fillBooked(
                int strategyId,
                int listingId,
                long clientOidCounter,
                long cumQtyAfter,
                Side side,
                long qty,
                long price,
                long fee,
                long eventTimeNs,
                Liquidity liquidity,
                Position after) {}

        @Override
        public void orderClosed(
                int strategyId,
                int listingId,
                int exchangeId,
                long clientOidCounter,
                long filledQty,
                OrderState state,
                RejectReason rejectReason,
                long timeNs) {}

        @Override
        public void orderRefused(int listingId, RejectReason reason) {}

        @Override
        public boolean isFailing(long nowNs) {
            return false;
        }
    };

    /** The OMS accepted an order and is about to send it. */
    void orderOpened(
            int strategyId,
            int listingId,
            int exchangeId,
            long clientOidCounter,
            Side side,
            long price,
            long size,
            long timeNs);

    /** The venue acknowledged an order; the report carries the id the venue knows it by. */
    void orderAcked(int strategyId, int listingId, int exchangeId, long clientOidCounter, OrderExecutionReport report);

    /**
     * The OMS booked a fill; {@code after} is the strategy's position on the listing once it was applied, and
     * {@code liquidity} is null when the venue didn't say whether it made or took.
     */
    void fillBooked(
            int strategyId,
            int listingId,
            long clientOidCounter,
            long cumQtyAfter,
            Side side,
            long qty,
            long price,
            long fee,
            long eventTimeNs,
            Liquidity liquidity,
            Position after);

    /** An order reached a terminal state; {@code rejectReason} is set only when it ended rejected. */
    void orderClosed(
            int strategyId,
            int listingId,
            int exchangeId,
            long clientOidCounter,
            long filledQty,
            OrderState state,
            RejectReason rejectReason,
            long timeNs);

    /**
     * The OMS refused an order or a modify before it reached the venue, so no order record exists for it. Only
     * counted: a burst of refusals must never crowd out the events that positions depend on.
     */
    void orderRefused(int listingId, RejectReason reason);

    /**
     * Whether trading must stop because the ledger can't be trusted to hold what happens next: events were lost,
     * the registry has fenced this session, or recorded events have waited too long to be confirmed.
     */
    boolean isFailing(long nowNs);
}
