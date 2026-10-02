package group.gnometrading.oms;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.intent.IntentResolver;
import group.gnometrading.oms.intent.VenueCapabilities;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;

public final class OrderManagementSystem {

    private final Logger logger;

    private final OrderStateManager orderStateManager;
    private final PositionTracker positionTracker;
    private final RiskEngine riskEngine;
    private final SecurityMaster securityMaster;
    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final IntHashMap<IntentResolver> resolvers;
    private final Order riskCheckOrder = new Order();
    private final OrderExecutionReport syntheticReject = new OrderExecutionReport();
    private final RiskCheckingSink riskCheckingSink = new RiskCheckingSink();
    private long oidCounter;

    public OrderManagementSystem(
            Logger logger,
            OrderStateManager orderStateManager,
            PositionTracker positionTracker,
            RiskEngine riskEngine,
            SecurityMaster securityMaster,
            SharedPriceBuffer priceBuffer,
            PriceSlotRegistry priceSlotRegistry) {
        this.logger = logger;
        this.orderStateManager = orderStateManager;
        this.positionTracker = positionTracker;
        this.riskEngine = riskEngine;
        this.securityMaster = securityMaster;
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.resolvers = new IntHashMap<>(4);
    }

    private long nextOid() {
        return ++oidCounter;
    }

    private int resolveListingId(int exchangeId, long securityId) {
        return securityMaster.getListing(exchangeId, (int) securityId).listingId();
    }

    public void processIntent(Intent intent, ActionSink sink) {
        IntentResolver resolver = getOrCreateResolver(intent.decoder.strategyId());
        riskCheckingSink.delegate = sink;
        int listingId = resolveListingId(intent.decoder.exchangeId(), intent.decoder.securityId());
        resolver.resolve(intent, listingId, riskCheckingSink);
    }

    public void processExecutionReport(OrderExecutionReport report, ActionSink sink) {
        long counter = report.getClientOidCounter();
        TrackedOrder tracked = orderStateManager.getOrder(counter);
        if (tracked == null) {
            if (report.decoder.execType() != ExecType.CANCEL_REJECT) {
                logger.log(LogMessage.EXEC_REPORT_FOR_UNKNOWN_ORDER, counter);
            }
            return;
        }

        if (isFill(report) && !isApplicableFill(report, tracked, counter)) {
            return;
        }

        long leavesQtyBefore = tracked.getLeavesQty();
        int strategyId = tracked.getStrategyId();
        // TODO: Move this to when we get a generic market update
        int listingId = resolveListingId(report.decoder.exchangeId(), report.decoder.securityId());

        orderStateManager.applyExecutionReport(report);
        updatePositionTracking(report, tracked, strategyId, leavesQtyBefore, listingId);
        forwardToResolver(report, tracked, strategyId, listingId, sink);

        if (tracked.getState().isTerminal()) {
            orderStateManager.releaseOrder(tracked);
        }

        checkMarketRisk(strategyId, listingId, sink);
    }

    public boolean validateOrder(Order order) {
        return riskEngine.check(order, positionTracker, orderStateManager, 0, 0);
    }

    public void onOrderAccepted(Order order) {
        orderStateManager.trackOrder(order);
        int listingId = resolveListingId(order.decoder.exchangeId(), order.decoder.securityId());
        positionTracker.addStrategyLeaves(
                order.getClientOidStrategyId(), listingId, order.decoder.side(), order.decoder.size());
    }

    public Position getPosition(int listingId) {
        return positionTracker.getPosition(listingId);
    }

    public Position getStrategyPosition(int strategyId, int listingId) {
        return positionTracker.getStrategyPosition(strategyId, listingId);
    }

    public long getEffectiveQuantity(int strategyId, int listingId) {
        Position pos = positionTracker.getStrategyPosition(strategyId, listingId);
        return pos != null ? pos.getEffectiveQuantity() : 0;
    }

    public TrackedOrder getOrder(long clientOidCounter) {
        return orderStateManager.getOrder(clientOidCounter);
    }

    public OrderStateManager getOrderStateManager() {
        return orderStateManager;
    }

    public PositionTracker getPositionTracker() {
        return positionTracker;
    }

    public RiskEngine getRiskEngine() {
        return riskEngine;
    }

    public IntentResolver getIntentResolver() {
        return getOrCreateResolver(0);
    }

    public IntentResolver getIntentResolver(int strategyId) {
        return getOrCreateResolver(strategyId);
    }

    public IntentResolver getOrCreateResolver(int strategyId) {
        IntentResolver resolver = resolvers.get(strategyId);
        if (resolver == null) {
            resolver = new IntentResolver(this::nextOid, strategyId, this::listingSupportsNativeModify);
            resolvers.put(strategyId, resolver);
        }
        return resolver;
    }

    private boolean listingSupportsNativeModify(final int listingId) {
        final Listing listing = securityMaster.getListing(listingId);
        return listing != null
                && VenueCapabilities.supportsNativeModify(listing.exchange().exchangeCode());
    }

    private void checkMarketRisk(final int strategyId, final int listingId, final ActionSink sink) {
        if (riskEngine.checkMarketPolicies(strategyId, listingId, positionTracker, orderStateManager)) {
            riskEngine.haltStrategy(strategyId);
            cancelAllOpenOrders(strategyId, sink);
        } else {
            riskEngine.resumeStrategy(strategyId);
        }
    }

    private final CancelOrder marketRiskCancel = new CancelOrder();

    public void shutdownCancelAll(final ActionSink sink) {
        orderStateManager.forEachOrder(tracked -> {
            if (!tracked.getState().isTerminal()) {
                marketRiskCancel.encodeClientOid(tracked.getClientOidCounter(), tracked.getStrategyId());
                marketRiskCancel
                        .encoder
                        .exchangeId((short) tracked.getExchangeId())
                        .securityId(tracked.getSecurityId());
                sink.onCancel(marketRiskCancel);
            }
        });
    }

    private void cancelAllOpenOrders(final int strategyId, final ActionSink sink) {
        orderStateManager.forEachOrder(tracked -> {
            if (tracked.getStrategyId() == strategyId && !tracked.getState().isTerminal()) {
                marketRiskCancel.encodeClientOid(tracked.getClientOidCounter(), strategyId);
                marketRiskCancel
                        .encoder
                        .exchangeId((short) tracked.getExchangeId())
                        .securityId(tracked.getSecurityId());
                sink.onCancel(marketRiskCancel);
            }
        });
    }

    private static boolean isFill(OrderExecutionReport report) {
        ExecType exec = report.decoder.execType();
        return exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL;
    }

    /**
     * A fill without its quantity or price cannot be booked, and one that does not move the cumulative quantity
     * forward was already booked (venues redeliver). Applying either would corrupt the position.
     */
    private boolean isApplicableFill(OrderExecutionReport report, TrackedOrder tracked, long counter) {
        if (report.decoder.filledQty() == OrderExecutionReportDecoder.filledQtyNullValue()
                || report.decoder.fillPrice() == OrderExecutionReportDecoder.fillPriceNullValue()) {
            logger.log(LogMessage.INVALID_FILL_REPORT, counter);
            return false;
        }
        if (tracked.cumulativeQtyAfter(report) <= tracked.getFilledQty()) {
            logger.log(LogMessage.DUPLICATE_FILL_IGNORED, counter);
            return false;
        }
        return true;
    }

    /**
     * A partial fill takes its quantity off the working quantity; a terminal report takes off everything still
     * working, since a venue may finish an order with less filled than was working. Leaves the venue reports
     * mid-order are not used: during a pending modify they describe the order before the modify.
     */
    private void updatePositionTracking(
            OrderExecutionReport report, TrackedOrder tracked, int strategyId, long leavesQtyBefore, int listingId) {
        long noLongerWorking;
        if (tracked.getState().isTerminal()) {
            noLongerWorking = isFill(report) ? Math.max(leavesQtyBefore, report.decoder.filledQty()) : leavesQtyBefore;
        } else {
            noLongerWorking = isFill(report) ? report.decoder.filledQty() : 0;
        }
        if (noLongerWorking > 0) {
            positionTracker.removeStrategyLeaves(strategyId, listingId, tracked.getSide(), noLongerWorking);
        }
        if (isFill(report)) {
            long fee = report.decoder.fee() == OrderExecutionReportDecoder.feeNullValue() ? 0 : report.decoder.fee();
            positionTracker.applyStrategyFill(
                    strategyId,
                    listingId,
                    tracked.getSide(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    fee);
        }
    }

    private void forwardToResolver(
            OrderExecutionReport report, TrackedOrder tracked, int strategyId, int listingId, ActionSink sink) {
        IntentResolver resolver = resolvers.get(strategyId);
        riskCheckingSink.delegate = sink;
        resolver.onExecutionReport(
                report.decoder.exchangeId(),
                report.decoder.securityId(),
                listingId,
                report,
                tracked.getSide(),
                riskCheckingSink);
    }

    /**
     * Wraps an {@link ActionSink} to apply risk checks on new orders and modifies
     * before forwarding to the delegate. Cancels pass through unconditionally.
     * Pre-allocated and reused; the delegate is swapped before each use.
     */
    private final class RiskCheckingSink implements ActionSink {

        ActionSink delegate;

        @Override
        public void onNewOrder(final Order order) {
            final int strategyId = order.getClientOidStrategyId();
            final int listingId = resolveListingId(order.decoder.exchangeId(), order.decoder.securityId());
            final RejectReason violation =
                    exchangeConstraintViolation(listingId, order.decoder.price(), order.decoder.size());
            if (violation != null) {
                logger.log(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS, order.getClientOidCounter());
                emitNewOrderRejection(order, listingId, violation);
                return;
            }
            if (riskEngine.check(order, positionTracker, orderStateManager, strategyId, listingId)) {
                onOrderAccepted(order);
                delegate.onNewOrder(order);
            } else {
                logger.log(LogMessage.ORDER_REJECTED_RISK_CHECK, order.getClientOidCounter());
                emitNewOrderRejection(order, listingId, RejectReason.RISK_LIMIT_EXCEEDED);
            }
        }

        private void emitNewOrderRejection(final Order order, final int listingId, final RejectReason reason) {
            syntheticReject.encodeClientOid(order.getClientOidCounter(), order.getClientOidStrategyId());
            syntheticReject
                    .encoder
                    .exchangeId(order.decoder.exchangeId())
                    .securityId(order.decoder.securityId())
                    .orderId(0)
                    .execType(ExecType.REJECT)
                    .orderStatus(OrderStatus.REJECTED)
                    .rejectReason(reason)
                    .filledQty(0)
                    .fillPrice(OrderExecutionReportDecoder.fillPriceNullValue())
                    .cumulativeQty(0)
                    .leavesQty(0)
                    .timestampEvent(0)
                    .timestampRecv(0)
                    .fee(OrderExecutionReportDecoder.feeNullValue());
            syntheticReject.encoder.flags().clear();
            syntheticReject.encoder.liquidity(Liquidity.NULL_VAL);
            // Published before the resolver sees it: anything the resolver does next can re-enter this
            // sink and re-encode syntheticReject.
            delegate.onExecReport(syntheticReject);
            final IntentResolver resolver = resolvers.get(order.getClientOidStrategyId());
            if (resolver != null) {
                resolver.onExecutionReport(
                        order.decoder.exchangeId(),
                        order.decoder.securityId(),
                        listingId,
                        syntheticReject,
                        order.decoder.side(),
                        this);
            }
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            delegate.onCancel(cancel);
        }

        @Override
        public void onModify(final ModifyOrder modify) {
            final long counter = modify.getClientOidCounter();
            final TrackedOrder original = orderStateManager.getOrder(counter);
            if (original == null) {
                return;
            }
            // modify.size is the FIX order quantity, filled portion included. Risk, exchange constraints
            // and position leaves all concern what can still execute, which is that less the fills.
            final long newLeaves = Math.max(0, modify.decoder.size() - original.getFilledQty());
            riskCheckOrder.encodeClientOid(counter, original.getStrategyId());
            riskCheckOrder
                    .encoder
                    .exchangeId((short) modify.decoder.exchangeId())
                    .securityId((int) modify.decoder.securityId())
                    .side(original.getSide())
                    .price(modify.decoder.price())
                    .size(newLeaves)
                    .orderType(original.getOrderType())
                    .timeInForce(original.getTimeInForce());
            final int listingId = resolveListingId(modify.decoder.exchangeId(), modify.decoder.securityId());
            final RejectReason violation = exchangeConstraintViolation(listingId, modify.decoder.price(), newLeaves);
            if (violation != null) {
                logger.log(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS, counter);
                emitModifyRejection(modify, original, listingId, violation);
                return;
            }
            if (riskEngine.check(
                    riskCheckOrder, positionTracker, orderStateManager, original.getStrategyId(), listingId)) {
                positionTracker.removeStrategyLeaves(
                        original.getStrategyId(), listingId, original.getSide(), original.getLeavesQty());
                positionTracker.addStrategyLeaves(original.getStrategyId(), listingId, original.getSide(), newLeaves);
                original.modify(modify.decoder.price(), modify.decoder.size());
                delegate.onModify(modify);
            } else {
                logger.log(LogMessage.ORDER_REJECTED_RISK_CHECK, counter);
                emitModifyRejection(modify, original, listingId, RejectReason.RISK_LIMIT_EXCEEDED);
            }
        }

        private void emitModifyRejection(
                final ModifyOrder modify, final TrackedOrder original, final int listingId, final RejectReason reason) {
            syntheticReject.encodeClientOid(original.getClientOidCounter(), original.getStrategyId());
            syntheticReject
                    .encoder
                    .exchangeId(modify.decoder.exchangeId())
                    .securityId(modify.decoder.securityId())
                    .orderId(0)
                    .execType(ExecType.CANCEL_REJECT)
                    .orderStatus(OrderStatus.NEW)
                    .rejectReason(reason)
                    .filledQty(0)
                    .fillPrice(OrderExecutionReportDecoder.fillPriceNullValue())
                    .cumulativeQty(0)
                    .leavesQty(original.getLeavesQty())
                    .timestampEvent(0)
                    .timestampRecv(0)
                    .fee(OrderExecutionReportDecoder.feeNullValue());
            syntheticReject.encoder.flags().clear();
            syntheticReject.encoder.liquidity(Liquidity.NULL_VAL);
            delegate.onExecReport(syntheticReject);
            final IntentResolver resolver = resolvers.get(original.getStrategyId());
            if (resolver != null) {
                resolver.onExecutionReport(
                        modify.decoder.exchangeId(),
                        modify.decoder.securityId(),
                        listingId,
                        syntheticReject,
                        original.getSide(),
                        this);
            }
        }

        /**
         * The listing rule an order breaks, or null if it breaks none. A listing without a spec, or with a
         * zero field, is not checked on that field.
         */
        private RejectReason exchangeConstraintViolation(int listingId, long price, long size) {
            ListingSpec spec = securityMaster.getListingSpec(listingId);
            if (spec == null) {
                return null;
            }
            if (spec.lotSize() > 0 && size % spec.lotSize() != 0) {
                return RejectReason.INVALID_SIZE;
            }
            if (isOffTick(spec, price)) {
                return RejectReason.INVALID_PRICE;
            }
            return isBelowMinNotional(spec, listingId, price, size) ? RejectReason.INVALID_SIZE : null;
        }

        // A market order has no price to check.
        private boolean isOffTick(ListingSpec spec, long price) {
            return spec.tickSize() > 0 && price != OrderDecoder.priceNullValue() && price % spec.tickSize() != 0;
        }

        private boolean isBelowMinNotional(ListingSpec spec, int listingId, long price, long size) {
            if (spec.minNotional() <= 0) {
                return false;
            }
            long effectivePrice = price;
            if (effectivePrice <= 0 && priceSlotRegistry != null) {
                int slot = priceSlotRegistry.getSlot(listingId);
                if (slot != IntToIntHashMap.MISSING) {
                    effectivePrice = priceBuffer.readSpinning(slot);
                }
            }
            return size <= 0 || Position.notional(effectivePrice, size) < spec.minNotional();
        }
    }
}
