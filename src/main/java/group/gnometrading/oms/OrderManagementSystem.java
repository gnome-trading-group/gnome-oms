package group.gnometrading.oms;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.IntHashMap;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.intent.IntentResolver;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.sm.ListingSpec;

public final class OrderManagementSystem {

    private final Logger logger;

    private final OrderStateManager orderStateManager;
    private final PositionTracker positionTracker;
    private final RiskEngine riskEngine;
    private final SecurityMaster securityMaster;
    private final IntHashMap<IntentResolver> resolvers;
    private final Order riskCheckOrder = new Order();
    private final RiskCheckingSink riskCheckingSink = new RiskCheckingSink();
    private long oidCounter;

    public OrderManagementSystem(
            Logger logger,
            OrderStateManager orderStateManager,
            PositionTracker positionTracker,
            RiskEngine riskEngine,
            SecurityMaster securityMaster) {
        this.logger = logger;
        this.orderStateManager = orderStateManager;
        this.positionTracker = positionTracker;
        this.riskEngine = riskEngine;
        this.securityMaster = securityMaster;
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
        resolver.resolve(intent, riskCheckingSink);
    }

    public void processExecutionReport(OrderExecutionReport report, ActionSink sink) {
        long counter = report.getClientOidCounter();
        TrackedOrder tracked = orderStateManager.getOrder(counter);
        if (tracked == null) {
            throw new IllegalStateException(
                    "Received execution report for unknown order with clientOidCounter: " + counter);
        }

        long leavesQtyBefore = tracked.getLeavesQty();
        int strategyId = tracked.getStrategyId();

        orderStateManager.applyExecutionReport(report);
        updatePositionTracking(report, tracked, strategyId, leavesQtyBefore);
        forwardToResolver(report, tracked, strategyId, sink);

        if (tracked.getState().isTerminal()) {
            orderStateManager.releaseOrder(tracked);
        }

        // TODO: Move this to when we get a generic market update
        int listingId = resolveListingId(report.decoder.exchangeId(), report.decoder.securityId());
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
            resolver = new IntentResolver(this::nextOid, strategyId);
            resolvers.put(strategyId, resolver);
        }
        return resolver;
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

    private void updatePositionTracking(
            OrderExecutionReport report, TrackedOrder tracked, int strategyId, long leavesQtyBefore) {
        ExecType exec = report.decoder.execType();
        int listingId = resolveListingId(report.decoder.exchangeId(), report.decoder.securityId());
        if (exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL) {
            positionTracker.removeStrategyLeaves(strategyId, listingId, tracked.getSide(), report.decoder.filledQty());
            long fee = report.decoder.fee() == OrderExecutionReportDecoder.feeNullValue() ? 0 : report.decoder.fee();
            positionTracker.applyStrategyFill(
                    strategyId,
                    listingId,
                    tracked.getSide(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    fee);
        } else if (exec == ExecType.CANCEL || exec == ExecType.REJECT || exec == ExecType.EXPIRE) {
            if (leavesQtyBefore > 0) {
                positionTracker.removeStrategyLeaves(strategyId, listingId, tracked.getSide(), leavesQtyBefore);
            }
        }
    }

    private void forwardToResolver(OrderExecutionReport report, TrackedOrder tracked, int strategyId, ActionSink sink) {
        IntentResolver resolver = resolvers.get(strategyId);
        riskCheckingSink.delegate = sink;
        resolver.onExecutionReport(
                report.decoder.exchangeId(), report.decoder.securityId(), report, tracked.getSide(), riskCheckingSink);
    }

    /**
     * Wraps an {@link ActionSink} to apply risk checks on new orders and modifies
     * before forwarding to the delegate. Cancels pass through unconditionally.
     * Pre-allocated and reused; the delegate is swapped before each use.
     */
    private final class RiskCheckingSink implements ActionSink {

        ActionSink delegate;

        @Override
        public void onNewOrder(Order order) {
            final int strategyId = order.getClientOidStrategyId();
            final int listingId = resolveListingId(order.decoder.exchangeId(), order.decoder.securityId());
            if (!passesExchangeConstraints(listingId, order.decoder.price(), order.decoder.size())) {
                logger.log(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS, order.getClientOidCounter());
                return;
            }
            if (riskEngine.check(order, positionTracker, orderStateManager, strategyId, listingId)) {
                onOrderAccepted(order);
                delegate.onNewOrder(order);
            } else {
                logger.log(LogMessage.ORDER_REJECTED_RISK_CHECK, order.getClientOidCounter());
            }
        }

        @Override
        public void onCancel(CancelOrder cancel) {
            delegate.onCancel(cancel);
        }

        @Override
        public void onModify(ModifyOrder modify) {
            final long counter = modify.getClientOidCounter();
            final TrackedOrder original = orderStateManager.getOrder(counter);
            if (original == null) {
                return;
            }
            riskCheckOrder
                    .encoder
                    .exchangeId((short) modify.decoder.exchangeId())
                    .securityId((int) modify.decoder.securityId())
                    .side(original.getSide())
                    .price(modify.decoder.price())
                    .size(modify.decoder.size())
                    .orderType(original.getOrderType())
                    .timeInForce(original.getTimeInForce());
            final int listingId = resolveListingId(modify.decoder.exchangeId(), modify.decoder.securityId());
            if (!passesExchangeConstraints(listingId, modify.decoder.price(), modify.decoder.size())) {
                logger.log(LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS, counter);
                return;
            }
            if (riskEngine.check(
                    riskCheckOrder, positionTracker, orderStateManager, original.getStrategyId(), listingId)) {
                positionTracker.removeStrategyLeaves(
                        original.getStrategyId(), listingId, original.getSide(), original.getLeavesQty());
                positionTracker.addStrategyLeaves(
                        original.getStrategyId(), listingId, original.getSide(), modify.decoder.size());
                original.modify(modify.decoder.price(), modify.decoder.size());
                delegate.onModify(modify);
            } else {
                logger.log(LogMessage.ORDER_REJECTED_RISK_CHECK, counter);
            }
        }

        private boolean passesExchangeConstraints(int listingId, long price, long size) {
            ListingSpec spec = securityMaster.getListingSpec(listingId);
            if (spec == null) {
                return true;
            }
            if (spec.lotSize() > 0 && size % spec.lotSize() != 0) {
                return false;
            }
            return spec.minNotional() <= 0 || price * size >= spec.minNotional();
        }
    }
}
