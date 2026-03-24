package group.gnometrading.oms.order;

public class OmsReplaceOrder {

    private int exchangeId;
    private long securityId;
    private int strategyId;
    private long originalClientOid;
    private long newClientOid;
    private long price;
    private long size;

    public OmsReplaceOrder() {}

    public void set(int exchangeId, long securityId, int strategyId,
                    long originalClientOid, long newClientOid, long price, long size) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.strategyId = strategyId;
        this.originalClientOid = originalClientOid;
        this.newClientOid = newClientOid;
        this.price = price;
        this.size = size;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.strategyId = 0;
        this.originalClientOid = 0;
        this.newClientOid = 0;
        this.price = 0;
        this.size = 0;
    }

    public int exchangeId() { return exchangeId; }
    public long securityId() { return securityId; }
    public int strategyId() { return strategyId; }
    public long originalClientOid() { return originalClientOid; }
    public long newClientOid() { return newClientOid; }
    public long price() { return price; }
    public long size() { return size; }
}
