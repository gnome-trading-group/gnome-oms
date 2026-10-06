package group.gnometrading.oms;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntToIntHashMap;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.action.ActionSink;
import group.gnometrading.oms.intent.IntentResolver;
import group.gnometrading.oms.intent.VenueCapabilities;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.state.OrderState;
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
import group.gnometrading.schemas.Side;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.EpochNanoClock;

public final class OrderManagementSystem {

    private static final long RESWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final int RESWEEP_CHECK_PASSES = 1024;
    static final int LEDGER_CHECK_PASSES = 1024;
    // Listing rules, numbered as LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS names them.
    private static final int NO_RULE = -1;
    private static final int RULE_LOT_SIZE = 0;
    private static final int RULE_MIN_SIZE = 1;
    private static final int RULE_TICK = 2;
    private static final int RULE_MIN_NOTIONAL = 3;
    private static final int NOT_HALTED = -1;

    private final Logger logger;

    private final OrderStateManager orderStateManager;
    private final PositionTracker positionTracker;
    private final RiskEngine riskEngine;
    private final SecurityMaster securityMaster;
    private final SharedPriceBuffer priceBuffer;
    private final PriceSlotRegistry priceSlotRegistry;
    private final IntHashMap<IntentResolver> resolvers;
    // The halt cause last logged per strategy, so a halt is logged when it starts rather than for every order.
    private final Int2IntHashMap loggedHaltCauses = new Int2IntHashMap(NOT_HALTED);
    private final LedgerSink ledger;
    private final IntConsumer ledgerFailureLatch = this::latchForLedger;
    private int passesUntilLedgerCheck;
    private boolean ledgerFailing;
    private final Order riskCheckOrder = new Order();
    private final OrderExecutionReport syntheticReject = new OrderExecutionReport();
    private final RiskCheckingSink riskCheckingSink = new RiskCheckingSink();
    private final ScopeCanceller scopeCanceller = new ScopeCanceller();
    private final CancelOrder directCancel = new CancelOrder();
    private final long[] seenSlotVersions;
    private final MarkMoveChecker markMoveChecker = new MarkMoveChecker();
    private long seenPriceEpoch;
    private long nextResweepNanos;
    private int passesUntilResweepCheck;
    private long oidCounter;
    // Stamps the reports the OMS writes itself; simulated time in a backtest.
    private final EpochNanoClock clock;

    public OrderManagementSystem(
            Logger logger,
            OrderStateManager orderStateManager,
            PositionTracker positionTracker,
            RiskEngine riskEngine,
            SecurityMaster securityMaster,
            SharedPriceBuffer priceBuffer,
            PriceSlotRegistry priceSlotRegistry,
            LedgerSink ledger,
            EpochNanoClock clock) {
        this.logger = logger;
        this.ledger = ledger;
        this.clock = clock;
        this.orderStateManager = orderStateManager;
        this.positionTracker = positionTracker;
        this.riskEngine = riskEngine;
        this.securityMaster = securityMaster;
        this.priceBuffer = priceBuffer;
        this.priceSlotRegistry = priceSlotRegistry;
        this.seenSlotVersions = new long[priceBuffer.capacity()];
        this.resolvers = new IntHashMap<>(4);
    }

    private long nextOid() {
        return ++oidCounter;
    }

    private int resolveListingId(int exchangeId, long securityId) {
        return securityMaster.getListing(exchangeId, (int) securityId).listingId();
    }

    public void processIntent(Intent intent, ActionSink sink) {
        int listingId = resolveListingId(intent.decoder.exchangeId(), intent.decoder.securityId());
        // A killed or latched scope's orders have been cancelled; letting an intent through would re-arm a slot whose
        // pending cancel it overwrites. Stale risk is not a halt: the strategy can still pull its quotes, and
        // RiskCheckingSink rejects anything new.
        if (riskEngine.isHalted(intent.decoder.strategyId(), listingId)) {
            return;
        }
        IntentResolver resolver = getOrCreateResolver(intent.decoder.strategyId());
        riskCheckingSink.delegate = sink;
        resolver.resolve(intent, listingId, riskCheckingSink);
    }

    /**
     * Applies a newly published risk snapshot, cancelling every order in each scope it has just killed, and while
     * anything is killed or latched, sweeps those scopes again every second. Called on every pass of the OMS loop:
     * with nothing killed it costs the engine's checks; while killed, the clock is read once every
     * {@link #RESWEEP_CHECK_PASSES} passes.
     */
    public void applyRiskChanges(ActionSink sink) {
        riskCheckingSink.delegate = sink;
        riskEngine.applyChanges(scopeCanceller);
        if (!riskEngine.hasKills()) {
            nextResweepNanos = 0;
            passesUntilResweepCheck = 0;
            return;
        }
        if (--passesUntilResweepCheck > 0) {
            return;
        }
        passesUntilResweepCheck = RESWEEP_CHECK_PASSES;
        // A venue can refuse a cancel (a rate limit, a minimum order age), putting the order back to work while its
        // scope stays killed, so killed scopes are swept again until nothing in them is left open.
        final long now = clock.nanoTime();
        if (nextResweepNanos == 0) {
            nextResweepNanos = now + RESWEEP_INTERVAL_NANOS;
        } else if (now >= nextResweepNanos) {
            nextResweepNanos = now + RESWEEP_INTERVAL_NANOS;
            scopeCanceller.resweep = true;
            riskEngine.forEachKilledScope(scopeCanceller);
            scopeCanceller.resweep = false;
        }
    }

    /**
     * Halts every strategy, cancelling its working orders, once the ledger can't record what happens next. Called on
     * every pass of the OMS loop; reads the clock once every {@link #LEDGER_CHECK_PASSES} passes. A registry outage
     * must not let resting orders keep filling with nothing recorded, which a restart would then never know about.
     */
    public void checkLedger(ActionSink sink) {
        if (--passesUntilLedgerCheck > 0) {
            return;
        }
        passesUntilLedgerCheck = LEDGER_CHECK_PASSES;
        if (!ledger.isFailing(clock.nanoTime())) {
            ledgerFailing = false;
            return;
        }
        if (!ledgerFailing) {
            ledgerFailing = true;
            logger.log(LogMessage.LEDGER_FAILING_HALTED);
        }
        // Checked again while it lasts, so an operator resuming the strategy can't trade past a broken ledger.
        riskCheckingSink.delegate = sink;
        positionTracker.forEachStrategyId(ledgerFailureLatch);
    }

    private void latchForLedger(final int strategyId) {
        if (riskEngine.latch(strategyId)) {
            scopeCanceller.cancel(strategyId, IntentResolver.ALL_LISTINGS);
        }
    }

    /**
     * Re-checks loss limits for every strategy holding a position on a listing whose mark has moved since the last
     * call, so a breach is caught on the tick that causes it even with no order activity. Called at the end of each
     * OMS pass, after order handling; with no price change it costs one load.
     */
    public void checkMarkMoves(ActionSink sink) {
        final long epoch = priceBuffer.priceEpoch();
        if (epoch == seenPriceEpoch) {
            return;
        }
        seenPriceEpoch = epoch;
        if (!riskEngine.hasMarketPolicies()) {
            return;
        }
        riskCheckingSink.delegate = sink;
        for (int slot = 0; slot < priceSlotRegistry.count(); slot++) {
            final long version = priceBuffer.slotVersion(slot);
            if (version != seenSlotVersions[slot]) {
                seenSlotVersions[slot] = version;
                markMoveChecker.listingId = priceSlotRegistry.listingId(slot);
                positionTracker.forEachStrategyId(markMoveChecker);
            }
        }
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

        boolean bookFill = false;
        if (isFill(report)) {
            if (isMalformedFill(report)) {
                logger.log(LogMessage.INVALID_FILL_REPORT, counter);
                return;
            }
            bookFill = tracked.cumulativeQtyAfter(report) > tracked.getFilledQty();
            if (!bookFill) {
                logger.log(LogMessage.DUPLICATE_FILL_IGNORED, counter);
                // A partial fill with nothing new was already booked (venues redeliver). A final fill with nothing
                // new still ends the order, e.g. one whose last fill was reported as partial before an amend failed.
                if (report.decoder.execType() == ExecType.PARTIAL_FILL) {
                    return;
                }
            }
        }

        long workingBefore = tracked.workingQty();
        int strategyId = tracked.getStrategyId();
        int listingId = resolveListingId(report.decoder.exchangeId(), report.decoder.securityId());
        final boolean awaitingAck = tracked.getState() == OrderState.PENDING_NEW;

        orderStateManager.applyExecutionReport(report);
        updatePositionTracking(report, tracked, strategyId, workingBefore, listingId, bookFill);
        recordInLedger(report, tracked, strategyId, listingId, awaitingAck, bookFill);
        final boolean terminal = tracked.getState().isTerminal();
        if (terminal) {
            // Before the resolver runs: it may send the replacement queued behind this order's cancel.
            positionTracker.removeOpenOrder(strategyId, listingId);
        }
        forwardToResolver(report, tracked, strategyId, listingId, sink);

        if (terminal) {
            orderStateManager.releaseOrder(tracked);
        }

        checkMarketRisk(strategyId, listingId);
    }

    private void recordInLedger(
            OrderExecutionReport report,
            TrackedOrder tracked,
            int strategyId,
            int listingId,
            boolean awaitingAck,
            boolean bookFill) {
        final long counter = tracked.getClientOidCounter();
        if (awaitingAck && report.decoder.execType() != ExecType.REJECT) {
            ledger.orderAcked(strategyId, listingId, tracked.getExchangeId(), counter, report);
        }
        if (bookFill) {
            ledger.fillBooked(
                    strategyId,
                    listingId,
                    counter,
                    tracked.getFilledQty(),
                    tracked.getSide(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    feeOf(report),
                    eventTimeOf(report),
                    positionTracker.getStrategyPosition(strategyId, listingId));
        }
        if (tracked.getState().isTerminal()) {
            ledger.orderClosed(
                    strategyId, listingId, tracked.getExchangeId(), counter, tracked.getFilledQty(), clock.nanoTime());
        }
    }

    public void onOrderAccepted(Order order) {
        orderStateManager.trackOrder(order);
        int listingId = resolveListingId(order.decoder.exchangeId(), order.decoder.securityId());
        // Recorded before the order is sent, so a later session can recognise it on the venue even if this process
        // dies before the venue answers.
        ledger.orderOpened(
                order.getClientOidStrategyId(),
                listingId,
                order.decoder.exchangeId(),
                order.getClientOidCounter(),
                order.decoder.side(),
                order.decoder.price(),
                order.decoder.size(),
                clock.nanoTime());
        positionTracker.addStrategyLeaves(
                order.getClientOidStrategyId(), listingId, order.decoder.side(), order.decoder.size());
        positionTracker.addOpenOrder(order.getClientOidStrategyId(), listingId);
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

    /** A breach halts the strategy until an operator resumes it; a later recovery in PnL does not. */
    private void checkMarketRisk(final int strategyId, final int listingId) {
        if (riskEngine.checkMarketPolicies(strategyId, listingId, positionTracker, orderStateManager)
                && riskEngine.latch(strategyId)) {
            logger.log(LogMessage.STRATEGY_LATCHED_LOSS_LIMIT, strategyId, listingId, riskEngine.breachedPolicyId());
            scopeCanceller.cancel(strategyId, IntentResolver.ALL_LISTINGS);
        }
    }

    /** Checks each strategy with an open position on a listing whose mark just moved. */
    private final class MarkMoveChecker implements IntConsumer {
        int listingId;

        @Override
        public void accept(final int strategyId) {
            final Position position = positionTracker.getStrategyPosition(strategyId, listingId);
            // A flat position's PnL does not depend on the mark.
            if (position != null && position.netQuantity != 0 && !riskEngine.isStrategyHalted(strategyId)) {
                checkMarketRisk(strategyId, listingId);
            }
        }
    }

    /** Cancels every open order directly: the process is going away, so no slot needs to learn about it. */
    public void shutdownCancelAll(final ActionSink sink) {
        riskCheckingSink.delegate = sink;
        scopeCanceller.cancelEveryOrder();
    }

    /**
     * Cancels every order in a scope once. Resting orders are cancelled through their strategy's resolver so its
     * slots know the cancel was deliberate and no queued intent re-places them; take orders have no slot and
     * are cancelled directly. Allocation-free: one reused instance carries the scope.
     */
    private final class ScopeCanceller implements RiskEngine.KillHandler {

        private static final int ALL_STRATEGIES = Integer.MIN_VALUE;

        // Cancels pass through the risk-checking sink unconditionally, to whichever sink the caller set.
        private final ActionSink sink = riskCheckingSink;
        // A sweep after the first also cancels orders still awaiting the venue's ack, in case it never comes.
        boolean resweep;
        private int strategyFilter;
        private int listingFilter;
        private final Consumer<IntentResolver> resolverVisitor =
                resolver -> resolver.cancelAll(listingFilter, sink, resweep);
        private final Consumer<TrackedOrder> takeOrderVisitor = this::cancelIfTakeOrderInScope;
        private final Consumer<TrackedOrder> everyOrderVisitor = this::cancelIfOpen;

        @Override
        public void onEverythingKilled() {
            cancel(ALL_STRATEGIES, IntentResolver.ALL_LISTINGS);
        }

        @Override
        public void onStrategyKilled(final int strategyId) {
            cancel(strategyId, IntentResolver.ALL_LISTINGS);
        }

        @Override
        public void onListingKilled(final int listingId) {
            cancel(ALL_STRATEGIES, listingId);
        }

        @Override
        public void onStrategyListingKilled(final int strategyId, final int listingId) {
            cancel(strategyId, listingId);
        }

        void cancel(final int strategyId, final int listingId) {
            strategyFilter = strategyId;
            listingFilter = listingId;
            if (strategyId == ALL_STRATEGIES) {
                resolvers.forEachValue(resolverVisitor);
            } else {
                final IntentResolver resolver = resolvers.get(strategyId);
                if (resolver != null) {
                    resolver.cancelAll(listingId, sink, resweep);
                }
            }
            orderStateManager.forEachOrder(takeOrderVisitor);
        }

        void cancelEveryOrder() {
            orderStateManager.forEachOrder(everyOrderVisitor);
        }

        /** Orders no slot holds — take orders — are cancelled directly; slot orders went through their resolver. */
        private void cancelIfTakeOrderInScope(final TrackedOrder tracked) {
            if (tracked.getState().isTerminal()) {
                return;
            }
            if (strategyFilter != ALL_STRATEGIES && tracked.getStrategyId() != strategyFilter) {
                return;
            }
            final int listingId = resolveListingId(tracked.getExchangeId(), tracked.getSecurityId());
            if (listingFilter != IntentResolver.ALL_LISTINGS && listingId != listingFilter) {
                return;
            }
            final IntentResolver resolver = resolvers.get(tracked.getStrategyId());
            if (resolver == null || !resolver.ownsOrder(listingId, tracked.getSide(), tracked.getClientOidCounter())) {
                cancelDirectly(tracked);
            }
        }

        private void cancelIfOpen(final TrackedOrder tracked) {
            if (!tracked.getState().isTerminal()) {
                cancelDirectly(tracked);
            }
        }

        private void cancelDirectly(final TrackedOrder tracked) {
            directCancel.encodeClientOid(tracked.getClientOidCounter(), tracked.getStrategyId());
            directCancel.encoder.exchangeId((short) tracked.getExchangeId()).securityId(tracked.getSecurityId());
            sink.onCancel(directCancel);
        }
    }

    private static boolean isFill(OrderExecutionReport report) {
        ExecType exec = report.decoder.execType();
        return exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL;
    }

    private static long feeOf(OrderExecutionReport report) {
        return report.decoder.fee() == OrderExecutionReportDecoder.feeNullValue() ? 0 : report.decoder.fee();
    }

    private static long eventTimeOf(OrderExecutionReport report) {
        final long event = report.decoder.timestampEvent();
        return event == OrderExecutionReportDecoder.timestampEventNullValue() ? report.decoder.timestampRecv() : event;
    }

    /** A fill without its quantity or price cannot be booked. */
    private static boolean isMalformedFill(OrderExecutionReport report) {
        return report.decoder.filledQty() == OrderExecutionReportDecoder.filledQtyNullValue()
                || report.decoder.fillPrice() == OrderExecutionReportDecoder.fillPriceNullValue();
    }

    /** Keeps the position's working quantity equal to what the OMS has working on the order. */
    private void updatePositionTracking(
            OrderExecutionReport report,
            TrackedOrder tracked,
            int strategyId,
            long workingBefore,
            int listingId,
            boolean bookFill) {
        adjustLeaves(strategyId, listingId, tracked.getSide(), tracked.workingQty() - workingBefore);
        if (bookFill) {
            positionTracker.applyStrategyFill(
                    strategyId,
                    listingId,
                    tracked.getSide(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    feeOf(report));
        }
    }

    private void adjustLeaves(int strategyId, int listingId, Side side, long change) {
        if (change > 0) {
            positionTracker.addStrategyLeaves(strategyId, listingId, side, change);
        } else if (change < 0) {
            positionTracker.removeStrategyLeaves(strategyId, listingId, side, -change);
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
            final int rule =
                    brokenListingRule(listingId, order.decoder.side(), order.decoder.price(), order.decoder.size());
            if (rule != NO_RULE) {
                logConstraintRejection(order, listingId, rule);
                emitNewOrderRejection(order, listingId, rejectReasonFor(rule));
                return;
            }
            // A full order book is a limit like any other: refuse the order rather than fail the OMS.
            final boolean bookFull = orderStateManager.isFull();
            final RejectReason riskReject = bookFull
                    ? RejectReason.RISK_LIMIT_EXCEEDED
                    : riskEngine.check(order, positionTracker, orderStateManager, strategyId, listingId);
            if (riskReject == null) {
                loggedHaltCauses.remove(strategyId);
                onOrderAccepted(order);
                delegate.onNewOrder(order);
            } else {
                logRiskRejection(order, strategyId, listingId, riskReject, bookFull);
                emitNewOrderRejection(order, listingId, riskReject);
            }
        }

        /**
         * Names what refused the order: the policy it broke (0 for a limit with no registry policy), or why the
         * strategy is halted. A halt is logged once when it starts refusing orders, not for each order it refuses.
         */
        private void logRiskRejection(
                final Order order,
                final int strategyId,
                final int listingId,
                final RejectReason reason,
                final boolean bookFull) {
            if (reason == RejectReason.HALTED) {
                final int cause = riskEngine.haltCause();
                if (loggedHaltCauses.get(strategyId) != cause) {
                    loggedHaltCauses.put(strategyId, cause);
                    logger.log(
                            LogMessage.ORDER_REJECTED_HALTED,
                            order.getClientOidCounter(),
                            strategyId,
                            listingId,
                            cause);
                }
                return;
            }
            logger.log(
                    LogMessage.ORDER_REJECTED_RISK_CHECK,
                    order.getClientOidCounter(),
                    listingId,
                    order.decoder.side().value(),
                    order.decoder.price(),
                    order.decoder.size(),
                    bookFull ? 0 : riskEngine.violatedPolicyId());
        }

        private void logConstraintRejection(final Order order, final int listingId, final int rule) {
            logger.log(
                    LogMessage.ORDER_REJECTED_EXCHANGE_CONSTRAINTS,
                    order.getClientOidCounter(),
                    listingId,
                    order.decoder.side().value(),
                    order.decoder.price(),
                    order.decoder.size(),
                    rule);
        }

        private void emitNewOrderRejection(final Order order, final int listingId, final RejectReason reason) {
            final long now = clock.nanoTime();
            syntheticReject.encodeClientOid(order.getClientOidCounter(), order.getClientOidStrategyId());
            syntheticReject
                    .encoder
                    .exchangeId(order.decoder.exchangeId())
                    .securityId(order.decoder.securityId())
                    .execType(ExecType.REJECT)
                    .orderStatus(OrderStatus.REJECTED)
                    .rejectReason(reason)
                    .filledQty(0)
                    .fillPrice(OrderExecutionReportDecoder.fillPriceNullValue())
                    .cumulativeQty(0)
                    .leavesQty(0)
                    .timestampEvent(now)
                    .timestampRecv(now)
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
            final int rule = brokenListingRule(listingId, original.getSide(), modify.decoder.price(), newLeaves);
            if (rule != NO_RULE) {
                logConstraintRejection(riskCheckOrder, listingId, rule);
                emitModifyRejection(modify, original, listingId, rejectReasonFor(rule));
                return;
            }
            final RejectReason riskReject = riskEngine.check(
                    riskCheckOrder, positionTracker, orderStateManager, original.getStrategyId(), listingId);
            if (riskReject == null) {
                final long workingBefore = original.workingQty();
                original.modify(modify.decoder.price(), modify.decoder.size());
                adjustLeaves(
                        original.getStrategyId(), listingId, original.getSide(), original.workingQty() - workingBefore);
                delegate.onModify(modify);
            } else {
                logRiskRejection(riskCheckOrder, original.getStrategyId(), listingId, riskReject, false);
                emitModifyRejection(modify, original, listingId, riskReject);
            }
        }

        private void emitModifyRejection(
                final ModifyOrder modify, final TrackedOrder original, final int listingId, final RejectReason reason) {
            final long now = clock.nanoTime();
            syntheticReject.encodeClientOid(original.getClientOidCounter(), original.getStrategyId());
            syntheticReject
                    .encoder
                    .exchangeId(modify.decoder.exchangeId())
                    .securityId(modify.decoder.securityId())
                    .execType(ExecType.CANCEL_REJECT)
                    .orderStatus(OrderStatus.NEW)
                    .rejectReason(reason)
                    .filledQty(0)
                    .fillPrice(OrderExecutionReportDecoder.fillPriceNullValue())
                    .cumulativeQty(0)
                    .leavesQty(original.workingQty())
                    .timestampEvent(now)
                    .timestampRecv(now)
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
         * The listing rule an order breaks, or {@link #NO_RULE}. A listing without a spec, or with a zero field, is
         * not checked on that field.
         */
        private int brokenListingRule(int listingId, Side side, long price, long size) {
            ListingSpec spec = securityMaster.getListingSpec(listingId);
            if (spec == null) {
                return NO_RULE;
            }
            if (spec.lotSize() > 0 && size % spec.lotSize() != 0) {
                return RULE_LOT_SIZE;
            }
            if (size < spec.minSize()) {
                return RULE_MIN_SIZE;
            }
            if (isOffTick(spec, price)) {
                return RULE_TICK;
            }
            return isBelowMinNotional(spec, listingId, side, price, size) ? RULE_MIN_NOTIONAL : NO_RULE;
        }

        private RejectReason rejectReasonFor(int rule) {
            return rule == RULE_TICK ? RejectReason.INVALID_PRICE : RejectReason.INVALID_SIZE;
        }

        // A market order has no price to check.
        private boolean isOffTick(ListingSpec spec, long price) {
            return spec.tickSize() > 0 && price != OrderDecoder.priceNullValue() && price % spec.tickSize() != 0;
        }

        private boolean isBelowMinNotional(ListingSpec spec, int listingId, Side side, long price, long size) {
            if (spec.minNotional() <= 0) {
                return false;
            }
            long effectivePrice = price;
            if (effectivePrice <= 0 && priceSlotRegistry != null) {
                int slot = priceSlotRegistry.getSlot(listingId);
                if (slot != IntToIntHashMap.MISSING) {
                    effectivePrice = priceBuffer.executionPrice(slot, side);
                }
            }
            return size <= 0 || Position.notional(effectivePrice, size) < spec.minNotional();
        }
    }
}
