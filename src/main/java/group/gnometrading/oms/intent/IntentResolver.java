package group.gnometrading.oms.intent;

import group.gnometrading.collections.LongHashMap;
import group.gnometrading.oms.OrderCause;
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
import group.gnometrading.schemas.OrderRole;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

public final class IntentResolver {

    /** Passed to {@link #cancelAll} to cancel on every listing. */
    public static final int ALL_LISTINGS = Integer.MIN_VALUE;
    /** Passed to {@link #cancelAll} to cancel on every exchange; no exchange has id 0. */
    public static final int ALL_EXCHANGES = 0;

    private final LongSupplier oidSupplier;
    private final int strategyId;
    private final IntPredicate nativeModifyByListing;
    private final LongSupplier nanoClock;
    // The cause stamped on what this resolver sends, shared with the OMS; held aside while a queued intent is sent
    // under its own.
    private final OrderCause cause;
    private final OrderCause heldCause = new OrderCause();
    private final Order pendingOrder = new Order();
    private final CancelOrder pendingCancel = new CancelOrder();
    private final ModifyOrder pendingModify = new ModifyOrder();

    // Slots keyed by listingId — one bid and one ask slot per (exchange, security) listing
    private final LongHashMap<OrderSlot> bidSlots = new LongHashMap<>(4);
    private final LongHashMap<OrderSlot> askSlots = new LongHashMap<>(4);
    private final Consumer<OrderSlot> slotCanceller = this::cancelSlot;
    private final Consumer<OrderSlot> cancelRetrier = this::retryCancelIfDue;
    // Slots with a refused pull's cancel scheduled to go again, and the earliest time one is due.
    private int cancelRetriesScheduled;
    private long nextCancelRetryNanos = Long.MAX_VALUE;
    private long cancelRetryNowNanos;
    private ActionSink cancelRetryHandler;
    private int cancelListing;
    private int cancelExchange;
    private boolean cancelPendingToo;
    private ActionSink cancelHandler;

    /**
     * @param nativeModifyByListing whether a listing's venue can change a working order in place; asked
     *     once per listing, when its slots are created
     * @param nanoClock times the cooldown after a rate-limit refusal
     * @param cause what the OMS is acting on, which every action sent carries; a queued intent keeps a copy
     */
    public IntentResolver(
            LongSupplier oidSupplier,
            int strategyId,
            IntPredicate nativeModifyByListing,
            LongSupplier nanoClock,
            OrderCause cause) {
        this.oidSupplier = oidSupplier;
        this.cause = cause;
        this.nanoClock = nanoClock;
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
                if (slot.isCancelSentBeforeAck()) {
                    // Its cancel is already out: a queued pull has been sent, and a queued replacement waits for the
                    // CANCEL, as after any cancel.
                    slot.onNewAckedWhileCancelling();
                    if (slot.hasQueuedIntent() && slot.getQueuedSize() == 0) {
                        slot.clearQueuedIntent();
                    }
                    return;
                }
                if (slot.getState() == OrderSlot.State.PENDING_MODIFY) {
                    slot.onModifyConfirmed();
                } else {
                    slot.onNewAcked();
                }
                if (slot.hasQueuedIntent()) {
                    long qPrice = slot.getQueuedPrice();
                    long qSize = slot.getQueuedSize();
                    short qFlags = slot.getQueuedFlags();
                    takeQueuedCause(slot);
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
                    restoreCause();
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
                if (exec == ExecType.REJECT && report.decoder.rejectReason() == RejectReason.RATE_LIMITED) {
                    slot.onRateLimited(nanoClock.getAsLong());
                }
                slot.onTerminal();
                if (slot.hasQueuedIntent() && slot.getQueuedSize() > 0) {
                    long price = slot.getQueuedPrice();
                    long size = slot.getQueuedSize();
                    short qFlags = slot.getQueuedFlags();
                    takeQueuedCause(slot);
                    slot.clearQueuedIntent();
                    submitNew(exchangeId, securityId, side, price, size, qFlags, slot, handler);
                    restoreCause();
                } else {
                    slot.clearQueuedIntent();
                }
            }
            case CANCEL_REJECT -> {
                // The order remains live: revert the pending state. The cancel of an order wanted gone, or of one a
                // cancel-replace is moving, goes again after a pause that doubles while refusals continue; at once
                // would loop against a venue that keeps refusing (a rate limit, a minimum order age).
                if (slot.isPullWanted() || slot.isReplacementWaiting()) {
                    scheduleCancelRetry(slot);
                }
                if (slot.isCancelSentBeforeAck()) {
                    // Refused before the order was even acknowledged: it stays PENDING_NEW, and what is queued (a
                    // pull or a replacement) is acted on at its NEW, or by the retry if that comes first.
                    slot.onCancelBeforeAckRejected();
                } else if (slot.getState() == OrderSlot.State.PENDING_MODIFY) {
                    if (report.decoder.rejectReason() == RejectReason.RATE_LIMITED) {
                        slot.onRateLimited(nanoClock.getAsLong());
                    }
                    slot.onModifyRejected();
                    if (slot.hasQueuedIntent()) {
                        processQueuedIntentOnLive(exchangeId, securityId, slot, handler);
                    }
                } else if (slot.getState() == OrderSlot.State.PENDING_CANCEL) {
                    slot.onCancelRejected();
                    // Without native modify the queued replacement waits for the retry.
                    if (slot.supportsNativeModify() && slot.hasQueuedIntent()) {
                        processQueuedIntentOnLive(exchangeId, securityId, slot, handler);
                    }
                }
            }
            default -> {
                // Not a report the slot acts on.
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
        if (wantsOrder) {
            slot.clearPullWanted();
        } else if (slot.getState() != OrderSlot.State.EMPTY) {
            slot.markPullWanted();
        }

        switch (slot.getState()) {
            case EMPTY -> {
                if (wantsOrder) {
                    submitNew(exchangeId, securityId, side, snappedPrice, desiredSize, flags, slot, handler);
                }
            }
            case PENDING_NEW -> {
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize, flags, cause);
                } else {
                    slot.queueIntent(0, 0, (short) 0, cause);
                }
                // The NEW can trail the venue taking the order by hundreds of milliseconds, so a resting order that
                // has to go is cancelled now. A native modify waits for the NEW, as only a live order can be amended.
                final boolean differs =
                        !wantsOrder || slot.getActivePrice() != snappedPrice || slot.getRestingQty() != desiredSize;
                if (differs && !slot.isCancelSentBeforeAck() && (!wantsOrder || !slot.supportsNativeModify())) {
                    emitCancel(exchangeId, securityId, slot, handler);
                    slot.onCancelSentBeforeAck();
                }
            }
            case PENDING_MODIFY, PENDING_CANCEL -> {
                if (wantsOrder) {
                    slot.queueIntent(snappedPrice, desiredSize, flags, cause);
                } else {
                    slot.queueIntent(0, 0, (short) 0, cause);
                }
            }
            case LIVE -> {
                if (!wantsOrder) {
                    slot.clearQueuedIntent();
                    emitCancel(exchangeId, securityId, slot, handler);
                    slot.onCancelSubmitted();
                } else if (slot.getActivePrice() != snappedPrice || slot.getRestingQty() != desiredSize) {
                    changeLiveOrder(exchangeId, securityId, slot, snappedPrice, desiredSize, flags, handler);
                } else {
                    // What rests is what is wanted: a replacement still waiting on a refused cancel no longer is.
                    slot.clearQueuedIntent();
                }
            }
        }
    }

    private void processQueuedIntentOnLive(int exchangeId, long securityId, OrderSlot slot, ActionSink handler) {
        long qPrice = slot.getQueuedPrice();
        long qSize = slot.getQueuedSize();
        short qFlags = slot.getQueuedFlags();
        takeQueuedCause(slot);
        slot.clearQueuedIntent();
        if (qSize == 0) {
            emitCancel(exchangeId, securityId, slot, handler);
            slot.onCancelSubmitted();
        } else if (qPrice != slot.getActivePrice() || qSize != slot.getRestingQty()) {
            changeLiveOrder(exchangeId, securityId, slot, qPrice, qSize, qFlags, handler);
        }
        restoreCause();
    }

    /**
     * Sends what a queued intent wants under that intent's own cause, not the report that freed the slot: the order is
     * still the strategy's reaction to whatever it was handling then, only delayed. {@link #restoreCause} after.
     */
    private void takeQueuedCause(OrderSlot slot) {
        heldCause.copyFrom(cause);
        cause.copyFrom(slot.getQueuedCause());
    }

    private void restoreCause() {
        cause.copyFrom(heldCause);
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
        if (isCoolingDown(slot)) {
            return;
        }
        if (slot.supportsNativeModify()) {
            final long orderQty = slot.orderQtyForResting(restingQty);
            slot.onModifySubmitted(price, orderQty);
            emitModify(exchangeId, securityId, slot, price, orderQty, flags, handler);
        } else {
            // The CANCEL branch of onExecutionReport submits the queued target as a new order. Sized as
            // resting quantity, it is right whatever filled before the cancel landed.
            slot.queueIntent(price, restingQty, flags, cause);
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
        if (isCoolingDown(slot)) {
            return;
        }
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
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED)
                .role(OrderRole.QUOTE);
        pendingOrder.encoder.flags().clear();
        pendingOrder.encoder.flags().postOnly(OrderFlagsDecoder.postOnly((byte) flags));
        slot.onNewSubmitted(oid, price, size, flags);
        handler.onNewOrder(pendingOrder);
    }

    /**
     * Held back after a rate-limit refusal: the strategy's next intent once the cooldown is over places
     * what it then wants, so nothing is queued meanwhile.
     */
    private boolean isCoolingDown(OrderSlot slot) {
        return slot.hasCooldown() && slot.isCoolingDown(nanoClock.getAsLong());
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

        // A take says what it's for; a quote is always a quote, so only takes read the intent's role.
        OrderRole role = intent.decoder.takeRole() == OrderRole.NULL_VAL ? OrderRole.NONE : intent.decoder.takeRole();

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
                .timeInForce(TimeInForce.IMMEDIATE_OR_CANCELED)
                .role(role);
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
     * Cancels every resting order on {@code exchangeId} (or on every exchange, for {@link #ALL_EXCHANGES}) and
     * {@code listingId} (or on every listing, for {@link #ALL_LISTINGS}) and drops whatever the slots had queued, so
     * nothing is placed again once the cancels are acknowledged. An order not yet acknowledged is cancelled now, once;
     * with {@code cancelAwaitingAck} (a re-sweep) its cancel is sent again even if one is out, for when neither its
     * ack nor the cancel's answer ever comes. An order mid-modify is cancelled as soon as the modify is answered. A
     * refused cancel is sent again after a pause (see {@link #retryRefusedCancels}).
     */
    public void cancelAll(int exchangeId, int listingId, ActionSink handler, boolean cancelAwaitingAck) {
        cancelExchange = exchangeId;
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

    /**
     * Sends again each refused cancel worth retrying, a pull's or a cancel-replace's, once its pause is over.
     * Called on every pass of the OMS loop; with nothing refused it costs one comparison.
     */
    public void retryRefusedCancels(ActionSink handler) {
        if (cancelRetriesScheduled == 0) {
            return;
        }
        final long now = nanoClock.getAsLong();
        if (now < nextCancelRetryNanos) {
            return;
        }
        cancelRetryNowNanos = now;
        cancelRetryHandler = handler;
        nextCancelRetryNanos = Long.MAX_VALUE;
        bidSlots.forEachValue(cancelRetrier);
        askSlots.forEachValue(cancelRetrier);
        cancelRetryHandler = null;
    }

    /** Slots with a refused cancel waiting to go again; while any are, the loop reads the clock every pass. */
    int cancelRetriesScheduled() {
        return cancelRetriesScheduled;
    }

    private void scheduleCancelRetry(OrderSlot slot) {
        if (slot.scheduleCancelRetry(nanoClock.getAsLong())) {
            cancelRetriesScheduled++;
        }
        nextCancelRetryNanos = Math.min(nextCancelRetryNanos, slot.getCancelRetryAtNanos());
    }

    private void retryCancelIfDue(OrderSlot slot) {
        if (!slot.isCancelRetryScheduled()) {
            return;
        }
        if (slot.getCancelRetryAtNanos() > cancelRetryNowNanos) {
            nextCancelRetryNanos = Math.min(nextCancelRetryNanos, slot.getCancelRetryAtNanos());
            return;
        }
        slot.onCancelRetryDue();
        cancelRetriesScheduled--;
        if (!slot.isCancelRetryForActiveOrder() || (!slot.isPullWanted() && !slot.isReplacementWaiting())) {
            return;
        }
        if (slot.getState() == OrderSlot.State.LIVE) {
            retryOnLive(slot);
        } else if (slot.getState() == OrderSlot.State.PENDING_NEW && !slot.isCancelSentBeforeAck()) {
            retryBeforeNew(slot);
        }
        // Otherwise a cancel or modify is already out, and a refusal of it schedules the next try.
    }

    private void retryBeforeNew(OrderSlot slot) {
        if (slot.isPullWanted()) {
            slot.queueIntent(0, 0, (short) 0, cause);
        }
        emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelRetryHandler);
        slot.onCancelSentBeforeAck();
    }

    private void retryOnLive(OrderSlot slot) {
        if (slot.isPullWanted()) {
            slot.clearQueuedIntent();
            emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelRetryHandler);
            slot.onCancelSubmitted();
        } else {
            processQueuedIntentOnLive(slot.getExchangeId(), slot.getSecurityId(), slot, cancelRetryHandler);
        }
    }

    private void cancelSlot(OrderSlot slot) {
        if (cancelListing != ALL_LISTINGS && slot.getListingId() != cancelListing) {
            return;
        }
        if (cancelExchange != ALL_EXCHANGES && slot.getExchangeId() != cancelExchange) {
            return;
        }
        if (slot.getState() != OrderSlot.State.EMPTY) {
            slot.markPullWanted();
        }
        switch (slot.getState()) {
            case LIVE -> {
                slot.clearQueuedIntent();
                emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelHandler);
                slot.onCancelSubmitted();
            }
            case PENDING_NEW -> {
                // Cancelled at once, as a pull would be; a re-sweep sends it again even with one out, for when neither
                // the order's NEW nor the cancel's answer ever comes.
                slot.queueIntent(0, 0, (short) 0, cause);
                if (cancelPendingToo || !slot.isCancelSentBeforeAck()) {
                    emitCancel(slot.getExchangeId(), slot.getSecurityId(), slot, cancelHandler);
                    slot.onCancelSentBeforeAck();
                }
            }
                // Already acknowledged, so it is cancelled when the modify is answered. A cancel sent now would be
                // indistinguishable from the modify if the venue refused it.
            case PENDING_MODIFY -> slot.queueIntent(0, 0, (short) 0, cause);
            case PENDING_CANCEL -> slot.clearQueuedIntent();
            case EMPTY -> {
                // nothing working
            }
        }
    }
}
