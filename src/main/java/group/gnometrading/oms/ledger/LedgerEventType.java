package group.gnometrading.oms.ledger;

public enum LedgerEventType {
    FILL,
    ORDER_OPENED,
    ORDER_ACKED,
    ORDER_CLOSED,
    /** Events for this strategy and listing were lost, so its position can't be trusted until reviewed. */
    GAP,
    /** How many orders the OMS has refused on a listing for one reason so far this session. */
    REJECT_COUNT
}
