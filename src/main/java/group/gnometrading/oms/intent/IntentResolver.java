package group.gnometrading.oms.intent;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.IntentDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderFlagsDecoder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

public final class IntentResolver {

    /** Passed to {@link #cancelAll} to cancel on every listing. */
    public static final int ALL_LISTINGS = Integer.MIN_VALUE;

    private final LongSupplier oidSupplier;
    private final int strategyId;
    private final IntPredicate nativeModifyByListing;
    private final Order pendingOrder = new Order();
    private final CancelOrder pendingCancel = new CancelOrder();
    private final ModifyOrder pendingModify = new ModifyOrder();

    // Slots keyed by listingId — one bid and one ask slot per (exchange, security) listing
    private final LongHashMap<OrderSlot> bidSlots = new LongHashMap<>(4);
    private final LongHashMap<OrderSlot> askSlots = new LongHashMap<>(4);
    private final Consumer<OrderSlot> slotCanceller = this::cancelSlot;
    private int cancelListing;
    private boolean cancelPendingToo;
    private ActionSink cancelHandler;

    /**
     * @param nativeModifyByListing whether a listing's venue can change a working order in place; asked
     *     once per listing, when its slots are created
     */
    public IntentResolver(LongSupplier oidSupplier, int strategyId, IntPredicate nativeModifyByListing) {
        this.oidSupplier = oidSupplier;
        this.strategyId = strategyId;
        this.nativeModifyByListing = nativeModifyByListing;
    }

    public void resolve(Intent intent, int listingId, ActionSink handler) {
        int exchangeId = intent.decoder.exchangeId();
        long securityId = intent.decoder.securityId();

        long bidSize = intent.decoder.bidSize() == IntentDecoder.bidSizeNullValue() ? 0 : intent.decoder.bidSize();
        long askSize = intent.decoder.askSize() == IntentDecoder.askSizeNullValue() ? 0 : intent.decoder.askSize();
        long bidPrice = intent.decoder.bidPrice() == IntentDecoder.bidPriceNullValue() ? 0 : intent.decoder.bidPrice();
        long askPrice = intent.decoder.askPrice() == IntentDecoder.askPriceNullValue() ? 0 : intent.decoder.askPrice();
        short intentFlags = intent.decoder.flags().getRaw();

        resolveSide(
                exchangeId,
                securityId,
                Side.Bid,
                bidPrice,
                bidSize,
                intentFlags,
                getOrCreateSlot(bidSlots, listingId, exchangeId, securityId),
                handler);

        resolveSide(
                exchangeId,
                securityId,
                Side.Ask,
                askPrice,
                askSize,
                intentFlags,
                getOrCreateSlot(askSlots, listingId, exchangeId, securityId),
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
            int exchangeId,
            long securityId,
            int listingId,
            OrderExecutionReport report,
            Side side,
            ActionSink handler) {
        LongHashMap<OrderSlot> slots = side == Side.Bid ? bidSlots : askSlots;
        OrderSlot slot = slots.get(listingId);
        if (slot == null) {
            return;
        }

        long reportCounter = report.getClientOidCounter();
        if (reportCounter != slot.getActiveClientOid()) {
            return;
        }
        final long cumulativeQty = report.decoder.cumulativeQty();
        if (cumulativeQty != OrderExecutionReportDecoder.cumulativeQtyNullValue()) {
            slot.onCumulativeQty(cumulativeQty);
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
                    short qFlags = slot.getQueuedFlags();
                    if (qSize == 0) {
                        slot.clearQueuedIntent();
                        emitCancel(exchangeId, securityId, slot, handler);
                        slot.onCancelSubmitted();
                    } else if (qPrice != slot.getActivePrice() || qSize != slot.getRestingQty()) {
                        slot.clearQueuedIntent();
                        changeLiveOrder(exchangeId, securityId, slot, qPrice, qSize, qFlags, handler);
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
                    short qFlags = slot.getQueuedFlags();
                    slot.clearQueuedIntent();
                    submitNew(exchangeId, securityId, side, price, size, qFlags, slot, handler);
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
                    if (!slot.supportsNativeModify()) {
                        // Without native modify the queued intent is the replacement whose cancel was just
                        // refused. Re-sending it would cancel again and loop against a venue that keeps
                        // refusing (e.g. a minimum order age), so wait for the strategy's next intent.
                        slot.clearQueuedIntent();
                    } else if (slot.hasQueuedIntent()) {
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
            short flags,
            OrderSlot slot,
            ActionSink handler) {
        boolean wantsOrder = desiredSize > 0;

        switch (slot.getState()) {
            case EMPTY -> {
                if (wantsOrder) {
                    submitNew(exchangeId, securityId, side, snappedPrice, desiredSize, flags, slot, handler);
                }
            }
            case PENDING_NEW, PENDING_MODIFY, PENDING_CANCEL -> {
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize, flags);
                } else {
                    slot.queueIntent(0, 0, (short) 0);
                }
            }
            case LIVE -> {
                if (!wantsOrder) {
                    slot.clearQueuedIntent();
                    emitCancel(exchangeId, securityId, slot, handler);
                    slot.onCancelSubmitted();
                } else if (slot.getActivePrice() != snappedPrice || slot.getRestingQty() != desiredSize) {
                    changeLiveOrder(exchangeId, securityId, slot, snappedPrice, desiredSize, flags, handler);
                }
            }
        }
    }

    private void processQueuedIntentOnLive(int exchangeId, long securityId, OrderSlot slot, ActionSink handler) {
        long qPrice = slot.getQueuedPrice();
        long qSize = slot.getQueuedSize();
        short qFlags = slot.getQueuedFlags();
        slot.clearQueuedIntent();
        if (qSize == 0) {
            emitCancel(exchangeId, securityId, slot, handler);
            slot.onCancelSubmitted();
        } else if (qPrice != slot.getActivePrice() || qSize != slot.getRestingQty()) {
            changeLiveOrder(exchangeId, securityId, slot, qPrice, qSize, qFlags, handler);
        }
    }

    /**
     * Moves a live order to {@code price} with {@code restingQty} left working. The slot's state advances
     * before the action is emitted because a risk rejection reports back synchronously, and must find
     * the slot pending to unwind it.
     */
    private void changeLiveOrder(
            int exchangeId,
            long securityId,
            OrderSlot slot,
            long price,
            long restingQty,
            short flags,
            ActionSink handler) {
        if (slot.supportsNativeModify()) {
            final long orderQty = slot.orderQtyForResting(restingQty);
            slot.onModifySubmitted(price, orderQty);
            emitModify(exchangeId, securityId, slot, price, orderQty, flags, handler);
        } else {
            // The CANCEL branch of onExecutionReport submits the queued target as a new order. Sized as
            // resting quantity, it is right whatever filled before the cancel landed.
            slot.queueIntent(price, restingQty, flags);
            slot.onCancelSubmitted();
            emitCancel(exchangeId, securityId, slot, handler);
        }
    }

    private void emitCancel(int exchangeId, long securityId, OrderSlot slot, ActionSink handler) {
        pendingCancel.encodeClientOid(slot.getActiveClientOid(), strategyId);
        pendingCancel.encoder.exchangeId((short) exchangeId).securityId((int) securityId);
        handler.onCancel(pendingCancel);
    }

    /** {@code orderQty} is the FIX order quantity: the order's total size, including what has filled. */
    private void emitModify(
            int exchangeId,
            long securityId,
            OrderSlot slot,
            long price,
            long orderQty,
            short flags,
            ActionSink handler) {
        pendingModify.encodeClientOid(slot.getActiveClientOid(), strategyId);
        pendingModify
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .price(price)
                .size(orderQty)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        pendingModify.encoder.flags().clear();
        pendingModify.encoder.flags().postOnly(OrderFlagsDecoder.postOnly((byte) flags));
        handler.onModify(pendingModify);
    }

    private void submitNew(
            int exchangeId,
            long securityId,
            Side side,
            long price,
            long size,
            short flags,
            OrderSlot slot,
            ActionSink handler) {
        long oid = oidSupplier.getAsLong();
        pendingOrder.encodeClientOid(oid, strategyId);
        pendingOrder
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .price(price)
                .size(size)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        pendingOrder.encoder.flags().clear();
        pendingOrder.encoder.flags().postOnly(OrderFlagsDecoder.postOnly((byte) flags));
        slot.onNewSubmitted(oid, price, size, flags);
        handler.onNewOrder(pendingOrder);
    }

    private void resolveTake(Intent intent, long takeSize, ActionSink handler) {
        int exchangeId = intent.decoder.exchangeId();
        long securityId = intent.decoder.securityId();
        Side takeSide = intent.decoder.takeSide();
        OrderType orderType = intent.decoder.takeOrderType() == OrderType.NULL_VAL
                ? OrderType.MARKET
                : intent.decoder.takeOrderType();
        long price = orderType == OrderType.MARKET
                ? IntentDecoder.takeLimitPriceNullValue()
                : intent.decoder.takeLimitPrice();

        long oid = oidSupplier.getAsLong();
        pendingOrder.encodeClientOid(oid, strategyId);
        pendingOrder
                .encoder
                .exchangeId((short) exchangeId)
                .securityId((int) securityId)
                .price(price)
                .size(takeSize)
                .side(takeSide)
                .orderType(orderType)
                .timeInForce(TimeInForce.IMMEDIATE_OR_CANCELED);
        pendingOrder.encoder.flags().clear();
        handler.onNewOrder(pendingOrder);
    }

    private OrderSlot getOrCreateSlot(LongHashMap<OrderSlot> slots, int listingId, int exchangeId, long securityId) {
        OrderSlot slot = slots.get(listingId);
        if (slot == null) {
            slot = new OrderSlot(nativeModifyByListing.test(listingId), listingId, exchangeId, securityId);
            slots.put(listingId, slot);
        }
        return slot;
    }

    /**
     * Cancels every resting order on {@code listingId} (or on every listing, for {@link #ALL_LISTINGS}) and
     * drops whatever the slots had queued, so nothing is placed again once the cancels are acknowledged. An order
     * not yet acknowledged, or mid-modify, is cancelled as soon as its ack arrives; with {@code cancelAwaitingAck}
     * one never acknowledged is also cancelled now, for when that ack never comes.
     */
    public void cancelAll(int listingId, ActionSink handler, boolean cancelAwaitingAck) {
        cancelListing = listingId;
        cancelHandler = handler;
        cancelPendingToo = cancelAwaitingAck;
        bidSlots.forEachValue(slotCanceller);
        askSlots.forEachValue(slotCanceller);
    }

    /** Whether {@code clientOid} is the order this resolver's slot for that listing and side is working. */
    public boolean ownsOrder(int listingId, Side side, long clientOid) {
        final OrderSlot slot = (side == Side.Bid ? bidSlots : askSlots).get(listingId);
        return slot != null && slot.getState() != OrderSlot.State.EMPTY && slot.getActiveClientOid() == clientOid;
    }

    private void cancelSlot(OrderSlot slot) {
        if (cancelListing != ALL_LISTINGS && slot.getListingId() != cancelListing) {
            return;
        }
        switch (slot.getState()) {
            case LIVE -> {
                slot.clearQueuedIntent();
                emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelHandler);
                slot.onCancelSubmitted();
            }
            case PENDING_NEW -> {
                slot.queueIntent(0, 0, (short) 0);
                if (cancelPendingToo) {
                    emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelHandler);
                }
            }
                // Already acknowledged, so it is cancelled when the modify is answered. A cancel sent now would be
                // indistinguishable from the modify if the venue refused it.
            case PENDING_MODIFY -> slot.queueIntent(0, 0, (short) 0);
            case PENDING_CANCEL -> slot.clearQueuedIntent();
            case EMPTY -> {
                // nothing working
            }
        }
    }
}
