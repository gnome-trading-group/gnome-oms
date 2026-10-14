package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.position.Position;
import group.gnometrading.schemas.Side;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property-based invariant tests using seeded random event sequences.
 * Each test verifies that a given invariant holds after every event in a long random sequence.
 */
class OmsInvariantTest {

    private static final int ITERATIONS = 500;
    private static final int EVENTS_PER_ITERATION = 60;
    private static final int STRESS_EVENTS = 5000;
    private static final long PRICE_SCALE = 10_000_000L;
    private static final long SIZE_SCALE = 1_000_000L;

    // --- I1: position.netQuantity == algebraic sum of all fills ---

    @Test
    void invariant_positionEqualsNetFills() {
        for (int seed = 0; seed < ITERATIONS; seed++) {
            OmsTestHarness h = new OmsTestHarness();
            EventDriver driver = new EventDriver(h, new Random(seed));
            long totalNetFills = 0;

            for (int i = 0; i < EVENTS_PER_ITERATION; i++) {
                EventDriver.EventResult result = driver.nextEvent();
                if (result.wasFill()) {
                    totalNetFills += result.side() == Side.Bid ? result.filledQty() : -result.filledQty();
                }
                Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
                long netQty = pos != null ? pos.netQuantity : 0;
                assertTrue(
                        netQty == totalNetFills,
                        "Seed " + seed + " event " + i + ": netQuantity=" + netQty + " but expected sum of fills="
                                + totalNetFills);
            }
        }
    }

    // --- I2: leaves quantities are non-negative ---

    @Test
    void invariant_leavesNonNegative() {
        for (int seed = 0; seed < ITERATIONS; seed++) {
            OmsTestHarness h = new OmsTestHarness();
            EventDriver driver = new EventDriver(h, new Random(seed));

            for (int i = 0; i < EVENTS_PER_ITERATION; i++) {
                driver.nextEvent();
                Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
                if (pos != null) {
                    assertTrue(
                            pos.leavesBuyQty >= 0,
                            "Seed " + seed + " event " + i + ": leavesBuyQty=" + pos.leavesBuyQty);
                    assertTrue(
                            pos.leavesSellQty >= 0,
                            "Seed " + seed + " event " + i + ": leavesSellQty=" + pos.leavesSellQty);
                }
            }
        }
    }

    // --- I6: no IllegalStateException from order state manager (capacity management) ---

    @Test
    void invariant_noSlotCollision_highVolume() {
        OmsTestHarness h = new OmsTestHarness();
        EventDriver driver = new EventDriver(h, new Random(12345));

        for (int i = 0; i < STRESS_EVENTS; i++) {
            try {
                driver.nextEvent();
            } catch (IllegalStateException e) {
                throw new AssertionError("Slot collision at event " + i + ": " + e.getMessage(), e);
            }
        }
    }

    // --- I3: no leaves imbalance (firm position consistency) ---

    @Test
    void invariant_firmPositionNonNegativeCost() {
        for (int seed = 0; seed < ITERATIONS; seed++) {
            OmsTestHarness h = new OmsTestHarness();
            EventDriver driver = new EventDriver(h, new Random(seed + 1000));

            for (int i = 0; i < EVENTS_PER_ITERATION; i++) {
                driver.nextEvent();
                Position pos = h.getPosition(OmsTestHarness.LISTING_ID);
                // totalCost can be negative if short (that's valid), but it must not overflow catastrophically.
                // A sign of corruption would be a cost that disagrees with net position direction by orders of
                // magnitude.
                if (pos != null && pos.netQuantity != 0) {
                    long avgEntry = pos.getAvgEntryPrice();
                    // avgEntry must be reasonable: between 0 and 10*maxPrice (500*10=5000)
                    assertTrue(
                            avgEntry >= 0 && avgEntry <= 5000,
                            "Seed " + seed + " event " + i + ": avgEntry=" + avgEntry + " netQty=" + pos.netQuantity
                                    + " totalCost=" + pos.totalCost);
                }
            }
        }
    }

    // --- I4: the position's working quantity and notional are the sums over its working orders ---

    @Test
    void invariant_workingAmountsMatchThePositionsAccumulators() {
        for (int seed = 0; seed < ITERATIONS; seed++) {
            OmsTestHarness h = new OmsTestHarness();
            EventDriver driver = new EventDriver(h, new Random(seed + 2000), PRICE_SCALE, SIZE_SCALE);

            for (int i = 0; i < EVENTS_PER_ITERATION; i++) {
                driver.nextEvent();
                assertWorkingMatchesAccumulators(h, "Seed " + seed + " event " + i);
            }
            driver.cancelEverything();
            assertWorkingMatchesAccumulators(h, "Seed " + seed + " after cancelling everything");
            Position pos = h.getStrategyPosition(OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID);
            assertTrue(
                    pos == null
                            || (pos.leavesBuyQty == 0
                                    && pos.leavesSellQty == 0
                                    && pos.leavesBuyNotional == 0
                                    && pos.leavesSellNotional == 0),
                    "Seed " + seed + ": nothing open, nothing working");
        }
    }

    private static void assertWorkingMatchesAccumulators(OmsTestHarness h, String where) {
        long[] sums = new long[4];
        h.orderStateManager.forEachOrder(order -> {
            int offset = order.getSide() == Side.Bid ? 0 : 2;
            sums[offset] += order.workingQty();
            sums[offset + 1] += order.workingNotional();
        });
        for (Position pos : new Position[] {
            h.getStrategyPosition(OmsTestHarness.STRATEGY_ID, OmsTestHarness.LISTING_ID),
            h.getPosition(OmsTestHarness.LISTING_ID)
        }) {
            long[] held = pos == null
                    ? new long[4]
                    : new long[] {pos.leavesBuyQty, pos.leavesBuyNotional, pos.leavesSellQty, pos.leavesSellNotional};
            assertTrue(
                    Arrays.equals(sums, held),
                    where + ": orders " + Arrays.toString(sums) + " but position " + Arrays.toString(held));
        }
    }

    /**
     * Drives random valid OMS events: intents (new/modify/cancel) and exec reports
     * (acks/fills/cancels/rejects/cancel-rejects) for outstanding orders.
     */
    private static final class EventDriver {

        private final OmsTestHarness h;
        private final Random rng;
        private final List<OutstandingOrder> outstanding = new ArrayList<>();
        private final long priceScale;
        private final long sizeScale;

        EventDriver(OmsTestHarness h, Random rng) {
            this(h, rng, 1, 1);
        }

        /** Scales prices and sizes up, for checks that need notionals the fixed-point scale doesn't round away. */
        EventDriver(OmsTestHarness h, Random rng, long priceScale, long sizeScale) {
            this.h = h;
            this.rng = rng;
            this.priceScale = priceScale;
            this.sizeScale = sizeScale;
        }

        /**
         * The venue cancels every order the OMS still has open, and then any replacement the strategy's queued
         * intents send in their place.
         */
        void cancelEverything() {
            List<Long> open = openOrders();
            while (!open.isEmpty()) {
                for (long counter : open) {
                    h.injectCancel(counter);
                }
                open = openOrders();
            }
            outstanding.clear();
        }

        private List<Long> openOrders() {
            List<Long> open = new ArrayList<>();
            h.orderStateManager.forEachOrder(order -> {
                if (!order.getState().isTerminal()) {
                    open.add(order.getClientOidCounter());
                }
            });
            return open;
        }

        EventResult nextEvent() {
            boolean hasOutstanding = !outstanding.isEmpty();
            // 50% chance of intent, 50% chance of exec report (if orders outstanding)
            if (!hasOutstanding || rng.nextBoolean()) {
                return emitIntent();
            } else {
                return emitExecReport();
            }
        }

        private EventResult emitIntent() {
            // Randomly submit bid, ask, cancel, or both
            int choice = rng.nextInt(4);
            h.sink.clear();
            long priceBid = (100 + rng.nextInt(20)) * priceScale;
            long priceAsk = (120 + rng.nextInt(20)) * priceScale;
            long size = (1 + rng.nextInt(10)) * sizeScale;

            switch (choice) {
                case 0 -> {
                    h.submitBidIntent(priceBid, size);
                    trackNewOrders(Side.Bid);
                }
                case 1 -> {
                    h.submitAskIntent(priceAsk, size);
                    trackNewOrders(Side.Ask);
                }
                case 2 -> {
                    h.submitBothIntent(priceBid, size, priceAsk, size);
                    trackNewOrders(null);
                }
                case 3 -> h.submitCancelIntent();
            }
            return new EventResult(false, Side.Bid, 0);
        }

        private void trackNewOrders(Side expectedSide) {
            for (OmsTestHarness.NewOrderCapture cap : h.sink.newOrders) {
                outstanding.add(new OutstandingOrder(cap.clientOidCounter(), cap.side(), cap.size(), false));
            }
        }

        private EventResult emitExecReport() {
            int idx = rng.nextInt(outstanding.size());
            OutstandingOrder order = outstanding.get(idx);
            h.sink.clear();

            if (!order.acked) {
                // Always ack first
                h.injectAck(order.counter, order.size);
                order.acked = true;
                return new EventResult(false, order.side, 0);
            }

            int choice = rng.nextInt(5);
            switch (choice) {
                case 0 -> { // partial fill
                    long qty = 1 + rng.nextInt((int) Math.max(1, order.remainingQty - 1));
                    order.remainingQty -= qty;
                    long price = (100 + rng.nextInt(20)) * priceScale;
                    h.injectFill(order.counter, qty, price, order.size - order.remainingQty, order.remainingQty);
                    if (order.remainingQty == 0) {
                        outstanding.remove(idx);
                    }
                    return new EventResult(true, order.side, qty);
                }
                case 1 -> { // full fill
                    long qty = order.remainingQty;
                    long price = (100 + rng.nextInt(20)) * priceScale;
                    h.injectFill(order.counter, qty, price, order.size, 0);
                    outstanding.remove(idx);
                    return new EventResult(true, order.side, qty);
                }
                case 2 -> { // cancel
                    h.injectCancel(order.counter);
                    outstanding.remove(idx);
                    return new EventResult(false, order.side, 0);
                }
                case 3 -> { // reject
                    h.injectReject(order.counter);
                    outstanding.remove(idx);
                    return new EventResult(false, order.side, 0);
                }
                case 4 -> { // cancel-reject (cancel rejected, order still live)
                    h.injectCancelReject(order.counter);
                    return new EventResult(false, order.side, 0);
                }
            }
            return new EventResult(false, order.side, 0);
        }

        private static final class OutstandingOrder {
            final long counter;
            final Side side;
            final long size;
            long remainingQty;
            boolean acked;

            OutstandingOrder(long counter, Side side, long size, boolean acked) {
                this.counter = counter;
                this.side = side;
                this.size = size;
                this.remainingQty = size;
                this.acked = acked;
            }
        }

        record EventResult(boolean wasFill, Side side, long filledQty) {}
    }
}
