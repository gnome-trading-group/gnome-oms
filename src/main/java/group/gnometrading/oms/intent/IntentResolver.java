package group.gnometrading.oms.intent;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class IntentResolver {

    private static final Logger logger = Logger.getLogger(IntentResolver.class.getName());

    private final ClientOidGenerator oidGenerator;
    private final LongHashMap<Long> tickSizes;
    private final long defaultTickSize;
    private final int strategyId;
    private final OmsAction action = new OmsAction();

    // Slots keyed by securityId — one bid and one ask slot per instrument
    private final LongHashMap<OrderSlot> bidSlots = new LongHashMap<>(4);
    private final LongHashMap<OrderSlot> askSlots = new LongHashMap<>(4);

    public IntentResolver(ClientOidGenerator oidGenerator, long defaultTickSize) {
        this(oidGenerator, new LongHashMap<>(4), defaultTickSize, 0);
    }

    public IntentResolver(ClientOidGenerator oidGenerator, long defaultTickSize, int strategyId) {
        this(oidGenerator, new LongHashMap<>(4), defaultTickSize, strategyId);
    }

    public IntentResolver(
            ClientOidGenerator oidGenerator, LongHashMap<Long> tickSizes, long defaultTickSize, int strategyId) {
        this.oidGenerator = oidGenerator;
        this.tickSizes = tickSizes;
        this.defaultTickSize = defaultTickSize;
        this.strategyId = strategyId;
    }

    public void setTickSize(long securityId, long tickSize) {
        tickSizes.put(securityId, tickSize);
    }

    public void resolve(Intent intent, Consumer<OmsAction> actionConsumer) {
        long securityId = intent.getSecurityId();
        long tickSize = getTickSize(securityId);

        resolveSide(
                intent.getExchangeId(),
                securityId,
                intent.getStrategyId(),
                Side.Bid,
                snapBid(intent.getBidPrice(), tickSize),
                intent.getBidSize(),
                getOrCreateSlot(bidSlots, securityId),
                actionConsumer);

        resolveSide(
                intent.getExchangeId(),
                securityId,
                intent.getStrategyId(),
                Side.Ask,
                snapAsk(intent.getAskPrice(), tickSize),
                intent.getAskSize(),
                getOrCreateSlot(askSlots, securityId),
                actionConsumer);

        if (intent.hasTake()) {
            resolveTake(intent, actionConsumer);
        }
    }

    /**
     * Called when an execution report arrives for an order managed by this resolver.
     * May emit a new order action if a queued intent fires after cancel confirmation.
     */
    public void onExecutionReport(
            int exchangeId, long securityId, OmsExecutionReport report, Side side, Consumer<OmsAction> actionConsumer) {
        LongHashMap<OrderSlot> slots = side == Side.Bid ? bidSlots : askSlots;
        OrderSlot slot = slots.get(securityId);
        if (slot == null) {
            return;
        }

        if (report.clientOid() != slot.getActiveClientOid()) {
            return;
        }

        ExecType exec = report.execType();
        switch (exec) {
            case NEW -> {
                slot.onNewAcked();
                // If strategy changed intent while we were pending new, cancel and resubmit
                if (slot.hasQueuedIntent()) {
                    long qPrice = slot.getQueuedPrice();
                    long qSize = slot.getQueuedSize();
                    if (qSize == 0 || qPrice != slot.getActivePrice() || qSize != slot.getActiveSize()) {
                        action.asCancel().set(exchangeId, securityId, strategyId, slot.getActiveClientOid());
                        actionConsumer.accept(action);
                        slot.onCancelSubmitted();
                        if (qSize == 0) {
                            slot.clearQueuedIntent();
                        }
                    } else {
                        slot.clearQueuedIntent();
                    }
                }
            }
            case FILL -> {
                slot.onTerminal();
                slot.clearQueuedIntent();
                // Fully filled — slot is empty, no queued intent fires
                // (the fill changed position; strategy will re-evaluate next tick)
            }
            case PARTIAL_FILL -> {
                // Order still live, no state change
            }
            case CANCEL, REJECT, EXPIRE -> {
                slot.onTerminal();
                // If strategy queued an intent while we were pending cancel, fire it now
                if (slot.hasQueuedIntent() && slot.getQueuedSize() > 0) {
                    long price = slot.getQueuedPrice();
                    long size = slot.getQueuedSize();
                    slot.clearQueuedIntent();
                    submitNew(exchangeId, securityId, strategyId, side, price, size, slot, actionConsumer);
                } else {
                    slot.clearQueuedIntent();
                }
            }
            default -> {
                // CANCEL_REJECT: no state change
            }
        }
    }

    private void resolveSide(
            int exchangeId,
            long securityId,
            int sideStrategyId,
            Side side,
            long snappedPrice,
            long desiredSize,
            OrderSlot slot,
            Consumer<OmsAction> consumer) {
        boolean wantsOrder = desiredSize > 0;

        switch (slot.getState()) {
            case EMPTY -> {
                if (wantsOrder) {
                    submitNew(exchangeId, securityId, sideStrategyId, side, snappedPrice, desiredSize, slot, consumer);
                }
            }
            case PENDING_NEW -> {
                // Waiting for NEW ack — queue the intent if it differs
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize);
                } else {
                    slot.queueIntent(0, 0);
                }
            }
            case LIVE -> {
                if (!wantsOrder) {
                    // Cancel — strategy no longer wants an order on this side
                    action.asCancel().set(exchangeId, securityId, sideStrategyId, slot.getActiveClientOid());
                    consumer.accept(action);
                    slot.onCancelSubmitted();
                    slot.clearQueuedIntent();
                } else if (slot.getActivePrice() != snappedPrice || slot.getActiveSize() != desiredSize) {
                    // Price/size changed — cancel and queue the new intent
                    action.asCancel().set(exchangeId, securityId, sideStrategyId, slot.getActiveClientOid());
                    consumer.accept(action);
                    slot.onCancelSubmitted();
                    slot.queueIntent(snappedPrice, desiredSize);
                }
                // else: same price and size — no action
            }
            case PENDING_CANCEL -> {
                // Already canceling — just update the queued intent
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize);
                } else {
                    slot.clearQueuedIntent();
                }
            }
        }
    }

    private void submitNew(
            int exchangeId,
            long securityId,
            int sideStrategyId,
            Side side,
            long price,
            long size,
            OrderSlot slot,
            Consumer<OmsAction> consumer) {
        long oid = oidGenerator.next();
        action.asNewOrder()
                .set(
                        exchangeId,
                        securityId,
                        sideStrategyId,
                        oid,
                        side,
                        price,
                        size,
                        OrderType.LIMIT,
                        TimeInForce.GOOD_TILL_CANCELED);
        consumer.accept(action);
        slot.onNewSubmitted(oid, price, size);
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

    private OrderSlot getOrCreateSlot(LongHashMap<OrderSlot> slots, long securityId) {
        OrderSlot slot = slots.get(securityId);
        if (slot == null) {
            slot = new OrderSlot();
            slots.put(securityId, slot);
        }
        return slot;
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
