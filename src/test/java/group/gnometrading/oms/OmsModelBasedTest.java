package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.oms.position.Position;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.Side;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Model-based tests: runs random event sequences through both the real OMS and a reference model,
 * asserting they agree on position state after every event.
 */
class OmsModelBasedTest {

    // =========================================================================
    // ModelPosition — exact replica of Position.applyFill (including integer division)
    // =========================================================================

    static final class ModelPosition {
        long netQuantity;
        long totalCost;
        long realizedPnl;
        long totalFees;
        long leavesBuyQty;
        long leavesSellQty;

        // Must match Position.applyFill exactly (same integer division, same branches).
        void applyFill(Side side, long qty, long price, long fee) {
            long signedQty = (side == Side.Bid) ? qty : -qty;
            totalFees += fee;
            if (netQuantity == 0) {
                netQuantity = signedQty;
                totalCost = price * qty;
            } else if (Long.signum(netQuantity) == Long.signum(signedQty)) {
                totalCost += price * qty;
                netQuantity += signedQty;
            } else {
                long closeQty = Math.min(Math.abs(netQuantity), qty);
                long avgEntry = getAvgEntryPrice();
                if (netQuantity > 0) realizedPnl += closeQty * (price - avgEntry);
                else realizedPnl += closeQty * (avgEntry - price);
                long prevQty = netQuantity;
                netQuantity += signedQty;
                if (netQuantity == 0) totalCost = 0;
                else if (Long.signum(netQuantity) != Long.signum(prevQty)) totalCost = price * Math.abs(netQuantity);
                else totalCost = avgEntry * Math.abs(netQuantity);
            }
        }

        long getAvgEntryPrice() {
            return netQuantity == 0 ? 0 : totalCost / Math.abs(netQuantity);
        }

        void addLeaves(Side side, long qty) {
            if (side == Side.Bid) leavesBuyQty += qty;
            else leavesSellQty += qty;
        }

        void removeLeaves(Side side, long qty) {
            if (side == Side.Bid) leavesBuyQty = Math.max(0, leavesBuyQty - qty);
            else leavesSellQty = Math.max(0, leavesSellQty - qty);
        }
    }

    // =========================================================================
    // Reference model — faithful mirror of IntentResolver + position tracking
    // =========================================================================

    static final class ReferenceModel {
        final long[] oidCounter; // shared for multi-strategy; wrap in array for pass-by-reference
        final Map<Long, ModelSlot> bidSlots = new HashMap<>();
        final Map<Long, ModelSlot> askSlots = new HashMap<>();
        final Map<Long, ModelOrder> orders = new HashMap<>();
        final Map<Long, ModelOrder> takeOrders = new HashMap<>();
        final Map<Integer, ModelPosition> positions = new HashMap<>();

        ReferenceModel() {
            this(new long[] {0});
        }

        ReferenceModel(long[] sharedCounter) {
            this.oidCounter = sharedCounter;
        }

        void processTakeIntent(Side side, long securityId, int listingId, long size) {
            // A take intent has null bid/ask sizes (→ 0), mirroring IntentResolver.resolve which
            // calls resolveSide(Bid, 0, 0) and resolveSide(Ask, 0, 0) before resolveTake.
            // This overwrites any queued make intents with a cancel intent (size=0).
            resolveSide(Side.Bid, securityId, listingId, 0, 0, getOrCreateSlot(bidSlots, listingId));
            resolveSide(Side.Ask, securityId, listingId, 0, 0, getOrCreateSlot(askSlots, listingId));
            long oid = ++oidCounter[0];
            takeOrders.put(oid, new ModelOrder(side, securityId, listingId, size));
            getOrCreatePosition(listingId).addLeaves(side, size);
        }

        // Backward-compat single-security overload for existing tests
        void processIntent(long bidPrice, long bidSize, long askPrice, long askSize) {
            processIntent(OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID, bidPrice, bidSize, askPrice, askSize);
        }

        void processIntent(long securityId, int listingId, long bidPrice, long bidSize, long askPrice, long askSize) {
            resolveSide(Side.Bid, securityId, listingId, bidPrice, bidSize, getOrCreateSlot(bidSlots, listingId));
            resolveSide(Side.Ask, securityId, listingId, askPrice, askSize, getOrCreateSlot(askSlots, listingId));
        }

        private void resolveSide(Side side, long securityId, int listingId, long price, long size, ModelSlot slot) {
            boolean wants = size > 0;
            switch (slot.state) {
                case EMPTY -> {
                    if (wants) submitNew(side, securityId, listingId, price, size, slot);
                }
                case PENDING_NEW -> slot.queue(price, size);
                case LIVE -> {
                    if (!wants) {
                        slot.clearQueue();
                        slot.state = ModelSlot.State.PENDING_CANCEL;
                    } else if (price != slot.activePrice || size != slot.activeSize) {
                        doModify(side, price, size, slot);
                    }
                }
                case PENDING_MODIFY, PENDING_CANCEL -> slot.queue(price, size);
            }
        }

        private void submitNew(Side side, long securityId, int listingId, long price, long size, ModelSlot slot) {
            long oid = ++oidCounter[0];
            slot.state = ModelSlot.State.PENDING_NEW;
            slot.activeOid = oid;
            slot.activePrice = price;
            slot.activeSize = size;
            orders.put(oid, new ModelOrder(side, securityId, listingId, size));
            getOrCreatePosition(listingId).addLeaves(side, size);
        }

        private void doModify(Side side, long price, long size, ModelSlot slot) {
            ModelOrder order = orders.get(slot.activeOid);
            ModelPosition pos = getOrCreatePosition(order.listingId);
            pos.removeLeaves(side, order.leavesQty);
            pos.addLeaves(side, size);
            order.leavesQty = size;
            slot.pendingPrice = price;
            slot.pendingSize = size;
            slot.state = ModelSlot.State.PENDING_MODIFY;
        }

        void processExecReport(
                long oid, ExecType type, long filledQty, long fillPrice, long cumQty, long leavesQty, long fee) {
            ModelOrder order = orders.get(oid);
            boolean isTake = order == null;
            if (isTake) order = takeOrders.get(oid);
            if (order == null) return;

            Side side = order.side;
            ModelSlot slot = isTake ? null : (side == Side.Bid ? bidSlots : askSlots).get((long) order.listingId);
            if (!isTake && (slot == null || slot.activeOid != oid)) return;

            long leavesQtyBefore = order.leavesQty;
            ModelPosition pos = getOrCreatePosition(order.listingId);
            long effFee = (fee == OrderExecutionReportDecoder.feeNullValue()) ? 0 : fee;

            // mirrors OrderManagementSystem.updatePositionTracking
            if (type == ExecType.FILL || type == ExecType.PARTIAL_FILL) {
                pos.removeLeaves(side, filledQty);
                pos.applyFill(side, filledQty, fillPrice, effFee);
            } else if (type == ExecType.CANCEL || type == ExecType.REJECT || type == ExecType.EXPIRE) {
                if (leavesQtyBefore > 0) pos.removeLeaves(side, leavesQtyBefore);
            }

            // Take orders have no slot state machine — just update order tracking and return
            if (isTake) {
                switch (type) {
                    case PARTIAL_FILL -> {
                        order.leavesQty = leavesQty;
                        order.filledQty = cumQty;
                    }
                    case FILL, CANCEL, REJECT, EXPIRE -> takeOrders.remove(oid);
                    default -> {}
                }
                return;
            }

            // mirrors IntentResolver.onExecutionReport
            switch (type) {
                case NEW -> {
                    order.leavesQty = leavesQty;
                    if (slot.state == ModelSlot.State.PENDING_MODIFY) {
                        slot.state = ModelSlot.State.LIVE;
                        slot.activePrice = slot.pendingPrice;
                        slot.activeSize = slot.pendingSize;
                        slot.pendingPrice = 0;
                        slot.pendingSize = 0;
                    } else {
                        slot.state = ModelSlot.State.LIVE;
                    }
                    if (slot.hasQueued) fireQueuedOnLive(side, slot, order);
                }
                case FILL -> {
                    orders.remove(oid);
                    slot.onTerminal();
                    slot.clearQueue();
                }
                case PARTIAL_FILL -> {
                    order.leavesQty = leavesQty;
                    order.filledQty = cumQty;
                }
                case CANCEL, REJECT, EXPIRE -> {
                    long securityId = order.securityId;
                    int listingId = order.listingId;
                    orders.remove(oid);
                    slot.onTerminal();
                    if (slot.hasQueued && slot.queuedSize > 0) {
                        long qp = slot.queuedPrice;
                        long qs = slot.queuedSize;
                        slot.clearQueue();
                        submitNew(side, securityId, listingId, qp, qs, slot);
                    } else {
                        slot.clearQueue();
                    }
                }
                case CANCEL_REJECT -> {
                    if (slot.state == ModelSlot.State.PENDING_MODIFY) {
                        slot.state = ModelSlot.State.LIVE;
                        slot.pendingPrice = 0;
                        slot.pendingSize = 0;
                        if (slot.hasQueued) fireQueuedOnLive(side, slot, order);
                    } else if (slot.state == ModelSlot.State.PENDING_CANCEL) {
                        slot.state = ModelSlot.State.LIVE;
                        if (slot.hasQueued) fireQueuedOnLive(side, slot, order);
                    }
                }
                default -> {
                    /* NULL_VAL: no-op */
                }
            }
        }

        private void fireQueuedOnLive(Side side, ModelSlot slot, ModelOrder order) {
            long qp = slot.queuedPrice;
            long qs = slot.queuedSize;
            slot.clearQueue();
            if (qs == 0) {
                slot.state = ModelSlot.State.PENDING_CANCEL;
            } else if (qp != slot.activePrice || qs != slot.activeSize) {
                doModify(side, qp, qs, slot);
            }
        }

        private ModelSlot getOrCreateSlot(Map<Long, ModelSlot> slots, long listingId) {
            return slots.computeIfAbsent(listingId, k -> new ModelSlot());
        }

        ModelPosition getOrCreatePosition(int listingId) {
            return positions.computeIfAbsent(listingId, k -> new ModelPosition());
        }
    }

    static final class ModelSlot {
        enum State {
            EMPTY,
            PENDING_NEW,
            LIVE,
            PENDING_MODIFY,
            PENDING_CANCEL
        }

        State state = State.EMPTY;
        long activeOid;
        long activePrice;
        long activeSize;
        long pendingPrice;
        long pendingSize;
        long queuedPrice;
        long queuedSize;
        boolean hasQueued;

        void queue(long price, long size) {
            queuedPrice = price;
            queuedSize = size;
            hasQueued = true;
        }

        void clearQueue() {
            hasQueued = false;
            queuedPrice = 0;
            queuedSize = 0;
        }

        void onTerminal() {
            state = State.EMPTY;
            activeOid = 0;
            activePrice = 0;
            activeSize = 0;
            pendingPrice = 0;
            pendingSize = 0;
        }
    }

    static final class ModelOrder {
        final Side side;
        final long securityId;
        final int listingId;
        long leavesQty;
        long filledQty;

        ModelOrder(Side side, long securityId, int listingId, long size) {
            this.side = side;
            this.securityId = securityId;
            this.listingId = listingId;
            leavesQty = size;
        }
    }

    // =========================================================================
    // Test-driver state
    // =========================================================================

    static final class OutstandingOrder {
        final long oid;
        final Side side;
        final int strategyId;
        final long securityId;
        final int listingId;
        final boolean isTake;
        boolean acked;
        long leavesQty;
        long cumQty;

        OutstandingOrder(long oid, Side side, int strategyId, long securityId, int listingId, long size) {
            this(oid, side, strategyId, securityId, listingId, size, false);
        }

        OutstandingOrder(
                long oid, Side side, int strategyId, long securityId, int listingId, long size, boolean isTake) {
            this.oid = oid;
            this.side = side;
            this.strategyId = strategyId;
            this.securityId = securityId;
            this.listingId = listingId;
            this.isTake = isTake;
            leavesQty = size;
        }
    }

    // =========================================================================
    // Test methods
    // =========================================================================

    @Test
    void modelBased_bidSideOnly_200trials() {
        runTrials(new Random(12345L), 200, 60, true, false);
    }

    @Test
    void modelBased_askSideOnly_200trials() {
        runTrials(new Random(54321L), 200, 60, false, true);
    }

    @Test
    void modelBased_bothSides_300trials() {
        runTrials(new Random(99887L), 300, 80, true, true);
    }

    @Test
    void modelBased_mixedMakeAndTake_200trials() {
        runMixedMakeAndTakeTrials(new Random(55123L), 200, 80);
    }

    @Test
    void modelBased_multiSecurity_200trials() {
        runMultiSecurityTrials(new Random(77654L), 200, 60);
    }

    @Test
    void modelBased_multiStrategy_200trials() {
        runMultiStrategyTrials(new Random(33211L), 200, 60);
    }

    // =========================================================================
    // Single-security, single-strategy trial runner
    // =========================================================================

    private void runTrials(Random rng, int trials, int events, boolean useBid, boolean useAsk) {
        for (int t = 0; t < trials; t++) {
            runTrial(rng, events, useBid, useAsk, "trial=" + t);
        }
    }

    private void runTrial(Random rng, int events, boolean useBid, boolean useAsk, String ctx) {
        OmsTestHarness h = new OmsTestHarness();
        ReferenceModel model = new ReferenceModel();
        List<OutstandingOrder> outstanding = new ArrayList<>();
        Map<Long, OutstandingOrder> byOid = new HashMap<>();

        for (int ev = 0; ev < events; ev++) {
            String evCtx = ctx + " ev=" + ev;
            int prevNew = h.sink.newOrders.size();
            int prevMod = h.sink.modifies.size();

            boolean sendIntent = outstanding.isEmpty() || rng.nextInt(3) < 2;
            if (sendIntent) {
                long bidPrice = 0, bidSize = 0, askPrice = 0, askSize = 0;
                if (useBid && rng.nextInt(4) > 0) {
                    bidPrice = 100L + rng.nextInt(5);
                    bidSize = 1L + rng.nextInt(4);
                }
                if (useAsk && rng.nextInt(4) > 0) {
                    askPrice = 110L + rng.nextInt(5);
                    askSize = 1L + rng.nextInt(4);
                }
                h.submitBothIntent(bidPrice, bidSize, askPrice, askSize);
                model.processIntent(bidPrice, bidSize, askPrice, askSize);
            } else {
                int idx = rng.nextInt(outstanding.size());
                OutstandingOrder o = outstanding.get(idx);
                ExecType type = pickExecType(rng, o);
                long filledQty = 0, fillPrice = 0, cumQty = o.cumQty, leavesQty = o.leavesQty;
                long fee = 0;
                boolean terminal = false;

                switch (type) {
                    case NEW -> {
                        leavesQty = o.leavesQty;
                        o.acked = true;
                    }
                    case PARTIAL_FILL -> {
                        filledQty = 1 + rng.nextInt((int) (o.leavesQty - 1));
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        o.cumQty += filledQty;
                        o.leavesQty -= filledQty;
                        cumQty = o.cumQty;
                        leavesQty = o.leavesQty;
                    }
                    case FILL -> {
                        filledQty = o.leavesQty;
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        cumQty = o.cumQty + filledQty;
                        leavesQty = 0;
                        terminal = true;
                    }
                    case CANCEL, REJECT, EXPIRE -> terminal = true;
                    case CANCEL_REJECT -> {
                        /* no tracking change */
                    }
                    default -> {}
                }

                h.injectExecReport(
                        o.strategyId,
                        o.oid,
                        OmsTestHarness.EXCHANGE_ID,
                        (int) o.securityId,
                        type,
                        filledQty,
                        fillPrice,
                        cumQty,
                        leavesQty,
                        fee);
                model.processExecReport(o.oid, type, filledQty, fillPrice, cumQty, leavesQty, fee);

                if (terminal) {
                    outstanding.remove(idx);
                    byOid.remove(o.oid);
                }
            }

            captureNewOrders(
                    h,
                    prevNew,
                    prevMod,
                    outstanding,
                    byOid,
                    OmsTestHarness.STRATEGY_ID,
                    OmsTestHarness.SECURITY_ID,
                    OmsTestHarness.LISTING_ID);
            assertPositionMatches(
                    model.getOrCreatePosition(OmsTestHarness.LISTING_ID),
                    h.getPosition(OmsTestHarness.LISTING_ID),
                    evCtx);
        }
    }

    // =========================================================================
    // Mixed make + take trial runner
    // =========================================================================

    private void runMixedMakeAndTakeTrials(Random rng, int trials, int events) {
        for (int t = 0; t < trials; t++) {
            runMixedMakeAndTakeTrial(rng, events, "trial=" + t);
        }
    }

    private void runMixedMakeAndTakeTrial(Random rng, int events, String ctx) {
        OmsTestHarness h = new OmsTestHarness();
        ReferenceModel model = new ReferenceModel();
        List<OutstandingOrder> outstanding = new ArrayList<>();
        Map<Long, OutstandingOrder> byOid = new HashMap<>();

        for (int ev = 0; ev < events; ev++) {
            String evCtx = ctx + " ev=" + ev;
            int prevNew = h.sink.newOrders.size();
            int prevMod = h.sink.modifies.size();

            // 0-1: make intent, 2: take intent, 3-4: exec report (or make intent if empty)
            int action = outstanding.isEmpty() ? rng.nextInt(3) : rng.nextInt(5);

            if (action <= 1) {
                // Make intent
                long bidPrice = 0, bidSize = 0, askPrice = 0, askSize = 0;
                if (rng.nextInt(4) > 0) {
                    bidPrice = 100L + rng.nextInt(5);
                    bidSize = 1L + rng.nextInt(4);
                }
                if (rng.nextInt(4) > 0) {
                    askPrice = 110L + rng.nextInt(5);
                    askSize = 1L + rng.nextInt(4);
                }
                h.submitBothIntent(bidPrice, bidSize, askPrice, askSize);
                model.processIntent(bidPrice, bidSize, askPrice, askSize);
                captureNewOrders(
                        h,
                        prevNew,
                        prevMod,
                        outstanding,
                        byOid,
                        OmsTestHarness.STRATEGY_ID,
                        OmsTestHarness.SECURITY_ID,
                        OmsTestHarness.LISTING_ID);
            } else if (action == 2) {
                // Take intent
                Side takeSide = rng.nextBoolean() ? Side.Bid : Side.Ask;
                long takeSize = 1L + rng.nextInt(5);
                long oid = h.submitTakeIntent(takeSize, takeSide);
                model.processTakeIntent(takeSide, OmsTestHarness.SECURITY_ID, OmsTestHarness.LISTING_ID, takeSize);
                OutstandingOrder o = new OutstandingOrder(
                        oid,
                        takeSide,
                        OmsTestHarness.STRATEGY_ID,
                        OmsTestHarness.SECURITY_ID,
                        OmsTestHarness.LISTING_ID,
                        takeSize,
                        true);
                outstanding.add(o);
                byOid.put(oid, o);
            } else {
                // Exec report
                int idx = rng.nextInt(outstanding.size());
                OutstandingOrder o = outstanding.get(idx);
                ExecType type = pickExecType(rng, o);
                long filledQty = 0, fillPrice = 0, cumQty = o.cumQty, leavesQty = o.leavesQty;
                long fee = 0;
                boolean terminal = false;

                switch (type) {
                    case NEW -> {
                        leavesQty = o.leavesQty;
                        o.acked = true;
                    }
                    case PARTIAL_FILL -> {
                        filledQty = 1 + rng.nextInt((int) (o.leavesQty - 1));
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        o.cumQty += filledQty;
                        o.leavesQty -= filledQty;
                        cumQty = o.cumQty;
                        leavesQty = o.leavesQty;
                    }
                    case FILL -> {
                        filledQty = o.leavesQty;
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        cumQty = o.cumQty + filledQty;
                        leavesQty = 0;
                        terminal = true;
                    }
                    case CANCEL, REJECT, EXPIRE -> terminal = true;
                    case CANCEL_REJECT -> {}
                    default -> {}
                }

                h.injectExecReport(
                        o.strategyId,
                        o.oid,
                        OmsTestHarness.EXCHANGE_ID,
                        (int) o.securityId,
                        type,
                        filledQty,
                        fillPrice,
                        cumQty,
                        leavesQty,
                        fee);
                model.processExecReport(o.oid, type, filledQty, fillPrice, cumQty, leavesQty, fee);

                if (terminal) {
                    outstanding.remove(idx);
                    byOid.remove(o.oid);
                }

                captureNewOrders(
                        h,
                        prevNew,
                        prevMod,
                        outstanding,
                        byOid,
                        OmsTestHarness.STRATEGY_ID,
                        OmsTestHarness.SECURITY_ID,
                        OmsTestHarness.LISTING_ID);
            }

            assertPositionMatches(
                    model.getOrCreatePosition(OmsTestHarness.LISTING_ID),
                    h.getPosition(OmsTestHarness.LISTING_ID),
                    evCtx);
        }
    }

    // =========================================================================
    // Multi-security trial runner (2 securities, 1 strategy)
    // =========================================================================

    private static final long SEC_A = OmsTestHarness.SECURITY_ID; // 42
    private static final int LIST_A = OmsTestHarness.LISTING_ID; // 100
    private static final long SEC_B = 43;
    private static final int LIST_B = 101;

    private void runMultiSecurityTrials(Random rng, int trials, int events) {
        for (int t = 0; t < trials; t++) {
            runMultiSecurityTrial(rng, events, "trial=" + t);
        }
    }

    private void runMultiSecurityTrial(Random rng, int events, String ctx) {
        OmsTestHarness h = new OmsTestHarness();
        h.stubListing(OmsTestHarness.EXCHANGE_ID, (int) SEC_B, LIST_B, 0, 0);
        ReferenceModel model = new ReferenceModel();
        List<OutstandingOrder> outstanding = new ArrayList<>();
        Map<Long, OutstandingOrder> byOid = new HashMap<>();

        for (int ev = 0; ev < events; ev++) {
            String evCtx = ctx + " ev=" + ev;
            int prevNew = h.sink.newOrders.size();
            int prevMod = h.sink.modifies.size();

            // Pick a random security for this event
            boolean useSecA = rng.nextBoolean();
            long securityId = useSecA ? SEC_A : SEC_B;
            int listingId = useSecA ? LIST_A : LIST_B;

            long captureSecId = securityId;
            int captureListId = listingId;
            boolean sendIntent = outstanding.isEmpty() || rng.nextInt(3) < 2;
            if (sendIntent) {
                long bidPrice = 0, bidSize = 0, askPrice = 0, askSize = 0;
                if (rng.nextInt(4) > 0) {
                    bidPrice = 100L + rng.nextInt(5);
                    bidSize = 1L + rng.nextInt(4);
                }
                if (rng.nextInt(4) > 0) {
                    askPrice = 110L + rng.nextInt(5);
                    askSize = 1L + rng.nextInt(4);
                }
                h.submitBothIntent(OmsTestHarness.STRATEGY_ID, securityId, bidPrice, bidSize, askPrice, askSize);
                model.processIntent(securityId, listingId, bidPrice, bidSize, askPrice, askSize);
            } else {
                int idx = rng.nextInt(outstanding.size());
                OutstandingOrder o = outstanding.get(idx);
                captureSecId = o.securityId;
                captureListId = o.listingId;
                ExecType type = pickExecType(rng, o);
                long filledQty = 0, fillPrice = 0, cumQty = o.cumQty, leavesQty = o.leavesQty;
                long fee = 0;
                boolean terminal = false;

                switch (type) {
                    case NEW -> {
                        leavesQty = o.leavesQty;
                        o.acked = true;
                    }
                    case PARTIAL_FILL -> {
                        filledQty = 1 + rng.nextInt((int) (o.leavesQty - 1));
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        o.cumQty += filledQty;
                        o.leavesQty -= filledQty;
                        cumQty = o.cumQty;
                        leavesQty = o.leavesQty;
                    }
                    case FILL -> {
                        filledQty = o.leavesQty;
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        cumQty = o.cumQty + filledQty;
                        leavesQty = 0;
                        terminal = true;
                    }
                    case CANCEL, REJECT, EXPIRE -> terminal = true;
                    case CANCEL_REJECT -> {
                        /* no tracking change */
                    }
                    default -> {}
                }

                h.injectExecReport(
                        o.strategyId,
                        o.oid,
                        OmsTestHarness.EXCHANGE_ID,
                        (int) o.securityId,
                        type,
                        filledQty,
                        fillPrice,
                        cumQty,
                        leavesQty,
                        fee);
                model.processExecReport(o.oid, type, filledQty, fillPrice, cumQty, leavesQty, fee);

                if (terminal) {
                    outstanding.remove(idx);
                    byOid.remove(o.oid);
                }
            }

            captureNewOrders(
                    h, prevNew, prevMod, outstanding, byOid, OmsTestHarness.STRATEGY_ID, captureSecId, captureListId);

            // Assert both securities' positions match independently
            assertPositionMatches(model.getOrCreatePosition(LIST_A), h.getPosition(LIST_A), evCtx + " sec=A");
            assertPositionMatches(model.getOrCreatePosition(LIST_B), h.getPosition(LIST_B), evCtx + " sec=B");
        }
    }

    // =========================================================================
    // Multi-strategy trial runner (2 strategies, 1 security)
    // =========================================================================

    private static final int STRAT_A = OmsTestHarness.STRATEGY_ID; // 7
    private static final int STRAT_B = 8;

    private void runMultiStrategyTrials(Random rng, int trials, int events) {
        for (int t = 0; t < trials; t++) {
            runMultiStrategyTrial(rng, events, "trial=" + t);
        }
    }

    private void runMultiStrategyTrial(Random rng, int events, String ctx) {
        OmsTestHarness h = new OmsTestHarness();
        // Both strategies share one oid counter that matches the real OMS's single counter
        long[] sharedCounter = new long[] {0};
        ReferenceModel modelA = new ReferenceModel(sharedCounter);
        ReferenceModel modelB = new ReferenceModel(sharedCounter);
        ModelPosition firmModel = new ModelPosition();

        List<OutstandingOrder> outstanding = new ArrayList<>();
        Map<Long, OutstandingOrder> byOid = new HashMap<>();

        for (int ev = 0; ev < events; ev++) {
            String evCtx = ctx + " ev=" + ev;
            int prevNew = h.sink.newOrders.size();
            int prevMod = h.sink.modifies.size();

            // Pick a random strategy for this event
            int strategyId = rng.nextBoolean() ? STRAT_A : STRAT_B;
            ReferenceModel model = strategyId == STRAT_A ? modelA : modelB;
            int captureStrategyId = strategyId;

            boolean sendIntent = outstanding.isEmpty() || rng.nextInt(3) < 2;
            if (sendIntent) {
                long bidPrice = 0, bidSize = 0, askPrice = 0, askSize = 0;
                if (rng.nextInt(4) > 0) {
                    bidPrice = 100L + rng.nextInt(5);
                    bidSize = 1L + rng.nextInt(4);
                }
                if (rng.nextInt(4) > 0) {
                    askPrice = 110L + rng.nextInt(5);
                    askSize = 1L + rng.nextInt(4);
                }
                h.submitBothIntent(strategyId, OmsTestHarness.SECURITY_ID, bidPrice, bidSize, askPrice, askSize);
                model.processIntent(bidPrice, bidSize, askPrice, askSize);
            } else {
                int idx = rng.nextInt(outstanding.size());
                OutstandingOrder o = outstanding.get(idx);
                captureStrategyId = o.strategyId;
                ReferenceModel orderModel = o.strategyId == STRAT_A ? modelA : modelB;
                ExecType type = pickExecType(rng, o);
                long filledQty = 0, fillPrice = 0, cumQty = o.cumQty, leavesQty = o.leavesQty;
                long fee = 0;
                boolean terminal = false;

                switch (type) {
                    case NEW -> {
                        leavesQty = o.leavesQty;
                        o.acked = true;
                    }
                    case PARTIAL_FILL -> {
                        filledQty = 1 + rng.nextInt((int) (o.leavesQty - 1));
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        o.cumQty += filledQty;
                        o.leavesQty -= filledQty;
                        cumQty = o.cumQty;
                        leavesQty = o.leavesQty;
                    }
                    case FILL -> {
                        filledQty = o.leavesQty;
                        fillPrice = 100L + rng.nextInt(10);
                        fee = rng.nextInt(4) == 0 ? rng.nextInt(5) + 1L : 0L;
                        cumQty = o.cumQty + filledQty;
                        leavesQty = 0;
                        terminal = true;
                    }
                    case CANCEL, REJECT, EXPIRE -> terminal = true;
                    case CANCEL_REJECT -> {
                        /* no tracking change */
                    }
                    default -> {}
                }

                // Also update firm model for fills
                if (type == ExecType.FILL || type == ExecType.PARTIAL_FILL) {
                    long effFee = fee;
                    firmModel.removeLeaves(o.side, filledQty);
                    firmModel.applyFill(o.side, filledQty, fillPrice, effFee);
                } else if (type == ExecType.CANCEL || type == ExecType.REJECT || type == ExecType.EXPIRE) {
                    firmModel.removeLeaves(o.side, o.leavesQty);
                }

                h.injectExecReport(
                        o.strategyId,
                        o.oid,
                        OmsTestHarness.EXCHANGE_ID,
                        (int) o.securityId,
                        type,
                        filledQty,
                        fillPrice,
                        cumQty,
                        leavesQty,
                        fee);
                orderModel.processExecReport(o.oid, type, filledQty, fillPrice, cumQty, leavesQty, fee);

                if (terminal) {
                    outstanding.remove(idx);
                    byOid.remove(o.oid);
                }
            }

            // Sync firm model leaves for modifies BEFORE captureNewOrders updates o.leavesQty
            for (int j = prevMod; j < h.sink.modifies.size(); j++) {
                OmsTestHarness.ModifyCapture mod = h.sink.modifies.get(j);
                OutstandingOrder oo = byOid.get(mod.clientOidCounter());
                if (oo != null) {
                    firmModel.removeLeaves(oo.side, oo.leavesQty);
                    firmModel.addLeaves(oo.side, mod.size());
                }
            }

            // Capture new orders and update firm model leaves for new orders
            int prevNewSize = outstanding.size();
            captureNewOrders(
                    h,
                    prevNew,
                    prevMod,
                    outstanding,
                    byOid,
                    captureStrategyId,
                    OmsTestHarness.SECURITY_ID,
                    OmsTestHarness.LISTING_ID);
            for (int j = prevNewSize; j < outstanding.size(); j++) {
                OutstandingOrder oo = outstanding.get(j);
                firmModel.addLeaves(oo.side, oo.leavesQty);
            }

            // Assert per-strategy positions
            assertPositionMatches(
                    modelA.getOrCreatePosition(OmsTestHarness.LISTING_ID),
                    h.getStrategyPosition(STRAT_A, OmsTestHarness.LISTING_ID),
                    evCtx + " strat=A");
            assertPositionMatches(
                    modelB.getOrCreatePosition(OmsTestHarness.LISTING_ID),
                    h.getStrategyPosition(STRAT_B, OmsTestHarness.LISTING_ID),
                    evCtx + " strat=B");

            // Assert firm position
            Position firmPos = h.getPosition(OmsTestHarness.LISTING_ID);
            long fNet = firmPos == null ? 0 : firmPos.netQuantity;
            long fBuy = firmPos == null ? 0 : firmPos.leavesBuyQty;
            long fSell = firmPos == null ? 0 : firmPos.leavesSellQty;
            assertEquals(firmModel.netQuantity, fNet, evCtx + " firm netQuantity");
            assertEquals(firmModel.leavesBuyQty, fBuy, evCtx + " firm leavesBuyQty");
            assertEquals(firmModel.leavesSellQty, fSell, evCtx + " firm leavesSellQty");
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private void captureNewOrders(
            OmsTestHarness h,
            int prevNew,
            int prevMod,
            List<OutstandingOrder> outstanding,
            Map<Long, OutstandingOrder> byOid,
            int strategyId,
            long securityId,
            int listingId) {
        for (int j = prevNew; j < h.sink.newOrders.size(); j++) {
            OmsTestHarness.NewOrderCapture cap = h.sink.newOrders.get(j);
            OutstandingOrder o = new OutstandingOrder(
                    cap.clientOidCounter(), cap.side(), strategyId, securityId, listingId, cap.size());
            outstanding.add(o);
            byOid.put(o.oid, o);
        }
        for (int j = prevMod; j < h.sink.modifies.size(); j++) {
            OmsTestHarness.ModifyCapture mod = h.sink.modifies.get(j);
            OutstandingOrder o = byOid.get(mod.clientOidCounter());
            if (o != null) o.leavesQty = mod.size();
        }
    }

    private void assertPositionMatches(ModelPosition mp, Position rp, String ctx) {
        long rNet = rp == null ? 0 : rp.netQuantity;
        long rBuy = rp == null ? 0 : rp.leavesBuyQty;
        long rSell = rp == null ? 0 : rp.leavesSellQty;
        long rCost = rp == null ? 0 : rp.totalCost;
        long rPnl = rp == null ? 0 : rp.realizedPnl;
        long rFees = rp == null ? 0 : rp.totalFees;
        assertEquals(mp.netQuantity, rNet, ctx + " netQuantity");
        assertEquals(mp.leavesBuyQty, rBuy, ctx + " leavesBuyQty");
        assertEquals(mp.leavesSellQty, rSell, ctx + " leavesSellQty");
        assertEquals(mp.totalCost, rCost, ctx + " totalCost");
        assertEquals(mp.realizedPnl, rPnl, ctx + " realizedPnl");
        assertEquals(mp.totalFees, rFees, ctx + " totalFees");
    }

    private ExecType pickExecType(Random rng, OutstandingOrder o) {
        if (!o.acked) {
            int roll = rng.nextInt(10);
            if (roll < 6) return ExecType.NEW;
            if (roll < 9) return ExecType.REJECT;
            return ExecType.FILL;
        }
        if (o.leavesQty <= 1) {
            int roll = rng.nextInt(o.isTake ? 4 : 5);
            if (roll < 2) return ExecType.FILL;
            if (roll < 4) return ExecType.CANCEL;
            return ExecType.CANCEL_REJECT; // only reachable for make orders
        }
        int roll = rng.nextInt(o.isTake ? 9 : 10);
        if (roll < 2) return ExecType.FILL;
        if (roll < 4) return ExecType.PARTIAL_FILL;
        if (roll < 6) return ExecType.CANCEL;
        if (roll < 7) return ExecType.CANCEL_REJECT; // only reachable for make orders
        if (roll < 8) return ExecType.EXPIRE;
        return ExecType.CANCEL;
    }
}
