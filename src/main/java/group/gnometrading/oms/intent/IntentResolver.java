package group.gnometrading.oms.intent;

import group.gnometrading.oms.order.OmsCancelOrder;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

import java.util.function.Consumer;

public class IntentResolver {

    private final OrderStateManager orderStateManager;
    private final ClientOidGenerator oidGenerator;

    private TrackedOrder currentBid;
    private TrackedOrder currentAsk;

    public IntentResolver(OrderStateManager orderStateManager, ClientOidGenerator oidGenerator) {
        this.orderStateManager = orderStateManager;
        this.oidGenerator = oidGenerator;
    }

    public void resolve(Intent intent, Consumer<OmsAction> actionConsumer) {
        // Resolve quote section (passive orders)
        if (intent.hasQuote()) {
            resolveQuote(intent, actionConsumer);
        }

        // Resolve take section (aggressive order)
        if (intent.hasTake()) {
            resolveTake(intent, actionConsumer);
        }
    }

    public void resolveAll(Intent[] intents, int count, Consumer<OmsAction> actionConsumer) {
        for (int i = 0; i < count; i++) {
            resolve(intents[i], actionConsumer);
        }
    }

    private void resolveTake(Intent intent, Consumer<OmsAction> actionConsumer) {
        long price = intent.getTakeOrderType() == OrderType.MARKET ? 0 : intent.getTakeLimitPrice();
        actionConsumer.accept(new OmsAction.NewOrder(
                new OmsOrder(
                        intent.getExchangeId(),
                        intent.getSecurityId(),
                        oidGenerator.next(),
                        intent.getTakeSide(),
                        price,
                        intent.getTakeSize(),
                        intent.getTakeOrderType(),
                        TimeInForce.IMMEDIATE_OR_CANCELED
                )
        ));
    }

    private void resolveQuote(Intent intent, Consumer<OmsAction> actionConsumer) {
        currentBid = null;
        currentAsk = null;

        orderStateManager.forEachOpenOrderFor(
                intent.getExchangeId(), intent.getSecurityId(),
                order -> {
                    if (order.getSide() == Side.Bid) currentBid = order;
                    else if (order.getSide() == Side.Ask) currentAsk = order;
                }
        );

        resolveSide(intent.getExchangeId(), intent.getSecurityId(),
                Side.Bid, intent.getBidPrice(), intent.getBidSize(),
                currentBid, actionConsumer);

        resolveSide(intent.getExchangeId(), intent.getSecurityId(),
                Side.Ask, intent.getAskPrice(), intent.getAskSize(),
                currentAsk, actionConsumer);
    }

    private void resolveSide(int exchangeId, long securityId,
                             Side side, long desiredPrice, long desiredSize,
                             TrackedOrder current, Consumer<OmsAction> consumer) {
        boolean hasOrder = current != null;
        boolean wantsOrder = desiredSize > 0;

        if (hasOrder && !wantsOrder) {
            consumer.accept(new OmsAction.Cancel(
                    new OmsCancelOrder(exchangeId, securityId, current.getClientOid())));
        } else if (!hasOrder && wantsOrder) {
            consumer.accept(new OmsAction.NewOrder(
                    new OmsOrder(exchangeId, securityId, oidGenerator.next(),
                            side, desiredPrice, desiredSize,
                            OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED)));
        } else if (hasOrder && wantsOrder) {
            OmsOrder orig = current.getOriginalOrder();
            if (orig.price() != desiredPrice || orig.size() != desiredSize) {
                consumer.accept(new OmsAction.Cancel(
                        new OmsCancelOrder(exchangeId, securityId, current.getClientOid())));
                consumer.accept(new OmsAction.NewOrder(
                        new OmsOrder(exchangeId, securityId, oidGenerator.next(),
                                side, desiredPrice, desiredSize,
                                OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED)));
            }
        }
    }
}
