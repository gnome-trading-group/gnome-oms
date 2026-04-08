package group.gnometrading.oms.intent;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ClientOidCodec;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Listing;

public final class IntentResolver {

    private final ClientOidGenerator oidGenerator;
    private final SecurityMaster securityMaster;
    private final int strategyId;
    private final byte[] clientOidBuf = new byte[ClientOidCodec.CLIENT_OID_LENGTH];

    private final Order pendingOrder = new Order();
    private final CancelOrder pendingCancel = new CancelOrder();
    private final ModifyOrder pendingModify = new ModifyOrder();

    // Slots keyed by securityId — one bid and one ask slot per instrument
    private final LongHashMap<OrderSlot> bidSlots = new LongHashMap<>(4);
    private final LongHashMap<OrderSlot> askSlots = new LongHashMap<>(4);

    public IntentResolver(ClientOidGenerator oidGenerator, SecurityMaster securityMaster, int strategyId) {
        this.oidGenerator = oidGenerator;
        this.securityMaster = securityMaster;
        this.strategyId = strategyId;
    }

    public void resolve(Intent intent, ActionSink handler) {
        int exchangeId = intent.decoder.exchangeId();
        long securityId = intent.decoder.securityId();
        long tickSize = getTickSize(exchangeId, securityId);

        long bidSize = intent.decoder.bidSize() == IntentDecoder.bidSizeNullValue() ? 0 : intent.decoder.bidSize();
        long askSize = intent.decoder.askSize() == IntentDecoder.askSizeNullValue() ? 0 : intent.decoder.askSize();
        long bidPrice = intent.decoder.bidPrice() == IntentDecoder.bidPriceNullValue() ? 0 : intent.decoder.bidPrice();
        long askPrice = intent.decoder.askPrice() == IntentDecoder.askPriceNullValue() ? 0 : intent.decoder.askPrice();

        resolveSide(
                exchangeId,
                securityId,
                Side.Bid,
                snapBid(bidPrice, tickSize),
                bidSize,
                getOrCreateSlot(bidSlots, securityId),
                handler);

        resolveSide(
                exchangeId,
                securityId,
                Side.Ask,
                snapAsk(askPrice, tickSize),
                askSize,
                getOrCreateSlot(askSlots, securityId),
                handler);

        long takeSize = intent.decoder.takeSize() == IntentDecoder.takeSizeNullValue() ? 0 : intent.decoder.takeSize();
        if (takeSize > 0) {
            resolveTake(intent, takeSize, handler);
        }
    }

    /**
     * Called when an execution report arrives for an order managed by this resolver.
     * May emit a new order action if a queued intent fires after cancel confirmation.
     */
    public void onExecutionReport(
            int exchangeId, long securityId, OrderExecutionReport report, Side side, ActionSink handler) {
        LongHashMap<OrderSlot> slots = side == Side.Bid ? bidSlots : askSlots;
        OrderSlot slot = slots.get(securityId);
        if (slot == null) {
            return;
        }

        long reportCounter = ClientOidCodec.decodeCounter(report.buffer, report.decoder.clientOidEncodingOffset());
        if (reportCounter != slot.getActiveClientOid()) {
            return;
        }

        ExecType exec = report.decoder.execType();
        switch (exec) {
            case NEW -> {
                if (slot.getState() == OrderSlot.State.PENDING_MODIFY) {
                    slot.onModifyConfirmed();
                } else {
                    slot.onNewAcked();
                }
                if (slot.hasQueuedIntent()) {
                    long qPrice = slot.getQueuedPrice();
                    long qSize = slot.getQueuedSize();
                    if (qSize == 0 || qPrice != slot.getActivePrice() || qSize != slot.getActiveSize()) {
                        emitCancel(exchangeId, securityId, slot);
                        handler.onCancel(pendingCancel);
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
            }
            case PARTIAL_FILL -> {
                // Order still live, no state change
            }
            case CANCEL, REJECT, EXPIRE -> {
                slot.onTerminal();
                if (slot.hasQueuedIntent() && slot.getQueuedSize() > 0) {
                    long price = slot.getQueuedPrice();
                    long size = slot.getQueuedSize();
                    slot.clearQueuedIntent();
                    submitNew(exchangeId, securityId, side, price, size, slot, handler);
                } else {
                    slot.clearQueuedIntent();
                }
            }
            default -> {
                // CANCEL_REJECT: order remains live, revert pending state
                if (slot.getState() == OrderSlot.State.PENDING_MODIFY) {
                    slot.onModifyRejected();
                    if (slot.hasQueuedIntent()) {
                        processQueuedIntentOnLive(exchangeId, securityId, slot, handler);
                    }
                } else if (slot.getState() == OrderSlot.State.PENDING_CANCEL) {
                    slot.onCancelRejected();
                    if (slot.hasQueuedIntent()) {
                        processQueuedIntentOnLive(exchangeId, securityId, slot, handler);
                    }
                }
            }
        }
    }

    private void resolveSide(
            int exchangeId,
            long securityId,
            Side side,
            long snappedPrice,
            long desiredSize,
            OrderSlot slot,
            ActionSink handler) {
        boolean wantsOrder = desiredSize > 0;

        switch (slot.getState()) {
            case EMPTY -> {
                if (wantsOrder) {
                    submitNew(exchangeId, securityId, side, snappedPrice, desiredSize, slot, handler);
                }
            }
            case PENDING_NEW -> {
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize);
                } else {
                    slot.queueIntent(0, 0);
                }
            }
            case LIVE -> {
                if (!wantsOrder) {
                    emitCancel(exchangeId, securityId, slot);
                    handler.onCancel(pendingCancel);
                    slot.onCancelSubmitted();
                    slot.clearQueuedIntent();
                } else if (slot.getActivePrice() != snappedPrice || slot.getActiveSize() != desiredSize) {
                    emitModify(exchangeId, securityId, slot, snappedPrice, desiredSize);
                    handler.onModify(pendingModify);
                    slot.onModifySubmitted(snappedPrice, desiredSize);
                }
            }
            case PENDING_MODIFY, PENDING_CANCEL -> {
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize);
                } else {
                    slot.clearQueuedIntent();
                }
            }
        }
    }

    private void processQueuedIntentOnLive(int exchangeId, long securityId, OrderSlot slot, ActionSink handler) {
        long qPrice = slot.getQueuedPrice();
        long qSize = slot.getQueuedSize();
        slot.clearQueuedIntent();
        if (qSize == 0) {
            emitCancel(exchangeId, securityId, slot);
            handler.onCancel(pendingCancel);
            slot.onCancelSubmitted();
        } else if (qPrice != slot.getActivePrice() || qSize != slot.getActiveSize()) {
            emitModify(exchangeId, securityId, slot, qPrice, qSize);
            handler.onModify(pendingModify);
            slot.onModifySubmitted(qPrice, qSize);
        }
    }

    private void emitCancel(int exchangeId, long securityId, OrderSlot slot) {
        ClientOidCodec.encode(clientOidBuf, slot.getActiveClientOid(), strategyId);
        pendingCancel
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .orderId(0)
                .putClientOid(clientOidBuf, 0, ClientOidCodec.CLIENT_OID_LENGTH);
    }

    private void emitModify(int exchangeId, long securityId, OrderSlot slot, long price, long size) {
        ClientOidCodec.encode(clientOidBuf, slot.getActiveClientOid(), strategyId);
        pendingModify
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .orderId(0)
                .putClientOid(clientOidBuf, 0, ClientOidCodec.CLIENT_OID_LENGTH)
                .price(price)
                .size((int) size)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
    }

    private void submitNew(
            int exchangeId, long securityId, Side side, long price, long size, OrderSlot slot, ActionSink handler) {
        long oid = oidGenerator.next();
        ClientOidCodec.encode(clientOidBuf, oid, strategyId);
        pendingOrder
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .putClientOid(clientOidBuf, 0, ClientOidCodec.CLIENT_OID_LENGTH)
                .price(price)
                .size((int) size)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        pendingOrder.encoder.flags().clear();
        handler.onNewOrder(pendingOrder);
        slot.onNewSubmitted(oid, price, size);
    }

    private void resolveTake(Intent intent, long takeSize, ActionSink handler) {
        int exchangeId = intent.decoder.exchangeId();
        long securityId = intent.decoder.securityId();
        Side takeSide = intent.decoder.takeSide();
        OrderType orderType = intent.decoder.takeOrderType() == OrderType.NULL_VAL
                ? OrderType.MARKET
                : intent.decoder.takeOrderType();
        long price = orderType == OrderType.MARKET ? 0 : intent.decoder.takeLimitPrice();

        long oid = oidGenerator.next();
        ClientOidCodec.encode(clientOidBuf, oid, strategyId);
        pendingOrder
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .putClientOid(clientOidBuf, 0, ClientOidCodec.CLIENT_OID_LENGTH)
                .price(price)
                .size((int) takeSize)
                .side(takeSide)
                .orderType(orderType)
                .timeInForce(TimeInForce.IMMEDIATE_OR_CANCELED);
        pendingOrder.encoder.flags().clear();
        handler.onNewOrder(pendingOrder);
    }

    private OrderSlot getOrCreateSlot(LongHashMap<OrderSlot> slots, long securityId) {
        OrderSlot slot = slots.get(securityId);
        if (slot == null) {
            slot = new OrderSlot();
            slots.put(securityId, slot);
        }
        return slot;
    }

    private long getTickSize(int exchangeId, long securityId) {
        Listing listing = securityMaster.getListing(exchangeId, (int) securityId);
        return securityMaster.getListingSpec(listing.listingId()).tickSize();
    }

    private static long snapBid(long price, long tickSize) {
        return (price / tickSize) * tickSize;
    }

    private static long snapAsk(long price, long tickSize) {
        return ((price + tickSize - 1) / tickSize) * tickSize;
    }
}
