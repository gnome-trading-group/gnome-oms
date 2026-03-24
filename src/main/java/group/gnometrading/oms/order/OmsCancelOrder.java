package group.gnometrading.oms.order;

public final class OmsCancelOrder {

    private int exchangeId;
    private long securityId;
    private int strategyId;
    private long clientOid;

    public OmsCancelOrder() {}

    @SuppressWarnings("checkstyle:HiddenField")
    public void set(int exchangeId, long securityId, int strategyId, long clientOid) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.strategyId = strategyId;
        this.clientOid = clientOid;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.strategyId = 0;
        this.clientOid = 0;
    }

    public int exchangeId() {
        return exchangeId;
    }

    public long securityId() {
        return securityId;
    }

    public int strategyId() {
        return strategyId;
    }

    public long clientOid() {
        return clientOid;
    }
}
