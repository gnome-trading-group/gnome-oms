package group.gnometrading.oms;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.oms.intent.ClientOidGenerator;
import group.gnometrading.oms.intent.Intent;
import group.gnometrading.oms.intent.IntentResolver;
import group.gnometrading.oms.intent.OmsAction;
import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.order.OmsReplaceOrder;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.RiskCheckResult;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.DefaultOrderStateManager;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.oms.state.TrackedOrder;
import group.gnometrading.schemas.ExecType;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class OrderManagementSystem {

    private static final Logger logger = Logger.getLogger(OrderManagementSystem.class.getName());

    private final OrderStateManager orderStateManager;
    private final PositionTracker positionTracker;
    private final RiskEngine riskEngine;
    private final ClientOidGenerator oidGenerator;
    private final long defaultTickSize;
    private final IntHashMap<IntentResolver> resolvers;
    private final OmsOrder riskCheckOrder = new OmsOrder();

    private Consumer<OmsAction> actionConsumer;

    public OrderManagementSystem(RiskEngine riskEngine, ClientOidGenerator oidGenerator, long defaultTickSize) {
        this(new DefaultOrderStateManager(), new DefaultPositionTracker(), riskEngine, oidGenerator, defaultTickSize);
    }

    public OrderManagementSystem(
            OrderStateManager orderStateManager,
            PositionTracker positionTracker,
            RiskEngine riskEngine,
            ClientOidGenerator oidGenerator,
            long defaultTickSize) {
        this.orderStateManager = orderStateManager;
        this.positionTracker = positionTracker;
        this.riskEngine = riskEngine;
        this.oidGenerator = oidGenerator;
        this.defaultTickSize = defaultTickSize;
        this.resolvers = new IntHashMap<>(4);
    }

    public void setActionConsumer(Consumer<OmsAction> actionConsumer) {
        this.actionConsumer = actionConsumer;
    }

    // --- Intent processing ---

    public void processIntent(Intent intent) {
        IntentResolver resolver = getOrCreateResolver(intent.getStrategyId());
        resolver.resolve(intent, this::onResolvedAction);
    }

    private void onResolvedAction(OmsAction action) {
        switch (action.type()) {
            case NEW_ORDER -> handleNewOrder(action);
            case REPLACE -> handleReplace(action);
            case CANCEL -> actionConsumer.accept(action);
        }
    }

    private void handleNewOrder(OmsAction action) {
        OmsOrder order = action.order();
        RiskCheckResult result = validateOrder(order);
        if (result instanceof RiskCheckResult.Approved) {
            onOrderAccepted(order);
            actionConsumer.accept(action);
        } else if (result instanceof RiskCheckResult.Rejected rejected) {
            logger.warning(
                    "Order " + order.clientOid() + " rejected by " + rejected.policyName() + ": " + rejected.reason());
        }
    }

    private void handleReplace(OmsAction action) {
        OmsReplaceOrder rep = action.replace();
        TrackedOrder original = orderStateManager.getOrder(rep.originalClientOid());
        if (original == null) {
            return;
        }
        riskCheckOrder.set(
                rep.exchangeId(),
                rep.securityId(),
                rep.strategyId(),
                rep.originalClientOid(),
                original.getSide(),
                rep.price(),
                rep.size(),
                original.getOrderType(),
                original.getTimeInForce());
        RiskCheckResult result = validateOrder(riskCheckOrder);
        if (result instanceof RiskCheckResult.Approved) {
            long oldLeaves = original.getLeavesQty();
            positionTracker.removeStrategyLeaves(
                    rep.strategyId(), rep.exchangeId(), rep.securityId(), original.getSide(), oldLeaves);
            positionTracker.addStrategyLeaves(
                    rep.strategyId(), rep.exchangeId(), rep.securityId(), original.getSide(), rep.size());
            original.amend(rep.price(), rep.size());
            actionConsumer.accept(action);
        } else if (result instanceof RiskCheckResult.Rejected rejected) {
            logger.warning("Replace " + rep.originalClientOid() + " rejected by " + rejected.policyName() + ": "
                    + rejected.reason());
        }
    }

    // --- Direct order management ---

    public RiskCheckResult validateOrder(OmsOrder order) {
        return riskEngine.check(order, positionTracker, orderStateManager);
    }

    public void onOrderAccepted(OmsOrder order) {
        orderStateManager.trackOrder(order);
        positionTracker.addStrategyLeaves(
                order.strategyId(), order.exchangeId(), order.securityId(), order.side(), order.size());
    }

    public void processExecutionReport(OmsExecutionReport report) {
        TrackedOrder tracked = orderStateManager.getOrder(report.clientOid());
        if (tracked == null) {
            return;
        }

        long leavesQtyBefore = tracked.getLeavesQty();
        int strategyId = tracked.getStrategyId();

        orderStateManager.applyExecutionReport(report);
        updatePositionTracking(report, tracked, strategyId, leavesQtyBefore);
        forwardToResolver(report, tracked, strategyId);

        if (tracked.getState().isTerminal()) {
            orderStateManager.releaseOrder(tracked);
        }
    }

    private void updatePositionTracking(
            OmsExecutionReport report, TrackedOrder tracked, int strategyId, long leavesQtyBefore) {
        ExecType exec = report.execType();
        if (exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL) {
            positionTracker.removeStrategyLeaves(
                    strategyId, report.exchangeId(), report.securityId(), tracked.getSide(), report.filledQty());
            positionTracker.applyStrategyFill(
                    strategyId,
                    report.exchangeId(),
                    report.securityId(),
                    tracked.getSide(),
                    report.filledQty(),
                    report.fillPrice(),
                    report.fee());
        } else if (exec == ExecType.CANCEL || exec == ExecType.REJECT || exec == ExecType.EXPIRE) {
            if (leavesQtyBefore > 0) {
                positionTracker.removeStrategyLeaves(
                        strategyId, report.exchangeId(), report.securityId(), tracked.getSide(), leavesQtyBefore);
            }
        }
    }

    private void forwardToResolver(OmsExecutionReport report, TrackedOrder tracked, int strategyId) {
        IntentResolver resolver = resolvers.get(strategyId);
        if (resolver == null || actionConsumer == null) {
            return;
        }
        resolver.onExecutionReport(
                report.exchangeId(), report.securityId(), report, tracked.getSide(), this::onResolvedAction);
    }

    // --- Query methods ---

    // --- Firm-level position queries ---

    public Position getPosition(int exchangeId, long securityId) {
        return positionTracker.getPosition(exchangeId, securityId);
    }

    public void forEachPosition(Consumer<Position> consumer) {
        positionTracker.forEachPosition(consumer);
    }

    // --- Per-strategy position queries ---

    public Position getStrategyPosition(int strategyId, int exchangeId, long securityId) {
        return positionTracker.getStrategyPosition(strategyId, exchangeId, securityId);
    }

    public void forEachStrategyPosition(int strategyId, Consumer<Position> consumer) {
        positionTracker.forEachStrategyPosition(strategyId, consumer);
    }

    public long getEffectiveQuantity(int strategyId, int exchangeId, long securityId) {
        Position pos = positionTracker.getStrategyPosition(strategyId, exchangeId, securityId);
        return pos != null ? pos.getEffectiveQuantity() : 0;
    }

    public TrackedOrder getOrder(long clientOid) {
        return orderStateManager.getOrder(clientOid);
    }

    public void forEachOpenOrder(Consumer<TrackedOrder> consumer) {
        orderStateManager.forEachOpenOrder(consumer);
    }

    public void forEachOpenOrderFor(int exchangeId, long securityId, Consumer<TrackedOrder> consumer) {
        orderStateManager.forEachOpenOrderFor(exchangeId, securityId, consumer);
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

    private IntentResolver getOrCreateResolver(int strategyId) {
        IntentResolver resolver = resolvers.get(strategyId);
        if (resolver == null) {
            resolver = new IntentResolver(oidGenerator, defaultTickSize, strategyId);
            resolvers.put(strategyId, resolver);
        }
        return resolver;
    }
}
