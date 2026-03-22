package group.gnometrading.oms.state;

public enum OrderState {
    PENDING_NEW,
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    EXPIRED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED || this == EXPIRED;
    }
}
