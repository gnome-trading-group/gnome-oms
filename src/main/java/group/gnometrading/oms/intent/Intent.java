package group.gnometrading.oms.intent;

import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;

/**
 * Flat union intent — single fixed-size struct for ring buffer slots.
 *
 * Supports quoting (passive resting orders) and taking (aggressive IOC orders)
 * simultaneously. Set fields to zero/null to disable a section.
 *
 * Quote fields: bidPrice, bidSize, askPrice, askSize
 *   - bidSize/askSize > 0 means desired resting order on that side
 *   - bidSize/askSize == 0 means cancel any existing order on that side
 *
 * Take fields: takeSide, takeSize, takeOrderType, takeLimitPrice
 *   - takeSize > 0 means emit an aggressive IOC order this tick
 *   - takeSize == 0 means no aggressive action
 */
public class Intent {

    private int exchangeId;
    private long securityId;

    // Quote section
    private long bidPrice;
    private long bidSize;
    private long askPrice;
    private long askSize;

    // Take section
    private Side takeSide;
    private long takeSize;
    private OrderType takeOrderType;
    private long takeLimitPrice;

    public Intent() {}

    public void setQuote(int exchangeId, long securityId,
                         long bidPrice, long bidSize,
                         long askPrice, long askSize) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
        this.takeSide = null;
        this.takeSize = 0;
        this.takeOrderType = null;
        this.takeLimitPrice = 0;
    }

    public void setTake(int exchangeId, long securityId,
                        Side side, long size,
                        OrderType orderType, long limitPrice) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.bidPrice = 0;
        this.bidSize = 0;
        this.askPrice = 0;
        this.askSize = 0;
        this.takeSide = side;
        this.takeSize = size;
        this.takeOrderType = orderType;
        this.takeLimitPrice = limitPrice;
    }

    public void setQuoteAndTake(int exchangeId, long securityId,
                                long bidPrice, long bidSize,
                                long askPrice, long askSize,
                                Side takeSide, long takeSize,
                                OrderType takeOrderType, long takeLimitPrice) {
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
        this.takeSide = takeSide;
        this.takeSize = takeSize;
        this.takeOrderType = takeOrderType;
        this.takeLimitPrice = takeLimitPrice;
    }

    public void reset() {
        this.exchangeId = 0;
        this.securityId = 0;
        this.bidPrice = 0;
        this.bidSize = 0;
        this.askPrice = 0;
        this.askSize = 0;
        this.takeSide = null;
        this.takeSize = 0;
        this.takeOrderType = null;
        this.takeLimitPrice = 0;
    }

    public boolean hasQuote() { return bidSize > 0 || askSize > 0; }
    public boolean hasTake() { return takeSize > 0; }

    public int getExchangeId() { return exchangeId; }
    public long getSecurityId() { return securityId; }
    public long getBidPrice() { return bidPrice; }
    public long getBidSize() { return bidSize; }
    public long getAskPrice() { return askPrice; }
    public long getAskSize() { return askSize; }
    public Side getTakeSide() { return takeSide; }
    public long getTakeSize() { return takeSize; }
    public OrderType getTakeOrderType() { return takeOrderType; }
    public long getTakeLimitPrice() { return takeLimitPrice; }
}
