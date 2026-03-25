package group.gnometrading.oms.intent;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.function.Consumer;

public final class IntentResolver {

    private final OrderStateManager orderStateManager;
    private final ClientOidGenerator oidGenerator;
    private final LongHashMap<Long> tickSizes;
    private final long defaultTickSize;
    private final int strategyId;
    private final OmsAction action = new OmsAction();

    private TrackedOrder currentBid;
    private TrackedOrder currentAsk;

    public IntentResolver(OrderStateManager orderStateManager, ClientOidGenerator oidGenerator, long defaultTickSize) {
        this(orderStateManager, oidGenerator, new LongHashMap<>(4), defaultTickSize, 0);
    }

    public IntentResolver(
            OrderStateManager orderStateManager,
            ClientOidGenerator oidGenerator,
            long defaultTickSize,
            int strategyId) {
        this(orderStateManager, oidGenerator, new LongHashMap<>(4), defaultTickSize, strategyId);
    }

    public IntentResolver(
            OrderStateManager orderStateManager,
            ClientOidGenerator oidGenerator,
            LongHashMap<Long> tickSizes,
            long defaultTickSize,
            int strategyId) {
        this.orderStateManager = orderStateManager;
        this.oidGenerator = oidGenerator;
        this.tickSizes = tickSizes;
        this.defaultTickSize = defaultTickSize;
        this.strategyId = strategyId;
    }

    public void setTickSize(long securityId, long tickSize) {
        tickSizes.put(securityId, tickSize);
    }

    public void resolve(Intent intent, Consumer<OmsAction> actionConsumer) {
        resolveQuote(intent, actionConsumer);

        if (intent.hasTake()) {
            resolveTake(intent, actionConsumer);
        }
    }

    private void resolveTake(Intent intent, Consumer<OmsAction> actionConsumer) {
        long price = intent.getTakeOrderType() == OrderType.MARKET ? 0 : intent.getTakeLimitPrice();
        action.asNewOrder()
                .set(
                        intent.getExchangeId(),
                        intent.getSecurityId(),
                        intent.getStrategyId(),
                        oidGenerator.next(),
                        intent.getTakeSide(),
                        price,
                        intent.getTakeSize(),
                        intent.getTakeOrderType(),
                        TimeInForce.IMMEDIATE_OR_CANCELED);
        actionConsumer.accept(action);
    }

    private void resolveQuote(Intent intent, Consumer<OmsAction> actionConsumer) {
        currentBid = null;
        currentAsk = null;

        orderStateManager.forEachOpenStrategyOrderFor(
                strategyId, intent.getExchangeId(), intent.getSecurityId(), order -> {
                    if (order.getSide() == Side.Bid) {
                        currentBid = order;
                    } else if (order.getSide() == Side.Ask) {
                        currentAsk = order;
                    }
                });

        long tickSize = getTickSize(intent.getSecurityId());

        resolveSide(
                intent.getExchangeId(),
                intent.getSecurityId(),
                intent.getStrategyId(),
                Side.Bid,
                snapBid(intent.getBidPrice(), tickSize),
                intent.getBidSize(),
                currentBid,
                actionConsumer);

        resolveSide(
                intent.getExchangeId(),
                intent.getSecurityId(),
                intent.getStrategyId(),
                Side.Ask,
                snapAsk(intent.getAskPrice(), tickSize),
                intent.getAskSize(),
                currentAsk,
                actionConsumer);
    }

    private void resolveSide(
            int exchangeId,
            long securityId,
            int sideStrategyId,
            Side side,
            long snappedPrice,
            long desiredSize,
            TrackedOrder current,
            Consumer<OmsAction> consumer) {
        boolean hasOrder = current != null;
        boolean wantsOrder = desiredSize > 0;

        if (hasOrder && !wantsOrder) {
            // Cancel — strategy no longer wants an order on this side
            action.asCancel().set(exchangeId, securityId, sideStrategyId, current.getClientOid());
            consumer.accept(action);
        } else if (!hasOrder && wantsOrder) {
            // New order — no existing order, strategy wants one
            action.asNewOrder()
                    .set(
                            exchangeId,
                            securityId,
                            sideStrategyId,
                            oidGenerator.next(),
                            side,
                            snappedPrice,
                            desiredSize,
                            OrderType.LIMIT,
                            TimeInForce.GOOD_TILL_CANCELED);
            consumer.accept(action);
        } else if (hasOrder && wantsOrder) {
            if (current.getPrice() != snappedPrice || current.getSize() != desiredSize) {
                // Price/size changed — cancel only. New order submitted next tick
                // when the cancel is confirmed and no open order exists.
                // This prevents two orders being live simultaneously on the same side.
                action.asCancel().set(exchangeId, securityId, sideStrategyId, current.getClientOid());
                consumer.accept(action);
            }
            // else: same grid price and size — no action
        }
    }

    private long getTickSize(long securityId) {
        Long ts = tickSizes.get(securityId);
        return ts != null ? ts : defaultTickSize;
    }

    private static long snapBid(long price, long tickSize) {
        return (price / tickSize) * tickSize;
    }

    private static long snapAsk(long price, long tickSize) {
        return ((price + tickSize - 1) / tickSize) * tickSize;
    }
}
