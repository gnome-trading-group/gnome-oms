package group.gnometrading.oms.risk.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.PositionTracker;
import group.gnometrading.oms.state.OrderStateManager;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceCollarPolicyTest {

    private static final long CENT = Statics.PRICE_SCALING_FACTOR / 100;
    private static final int LISTING_ID = 7;

    @Mock
    private PositionTracker positions;

    @Mock
    private OrderStateManager orders;

    private final SharedPriceBuffer prices = new SharedPriceBuffer(2);
    private final PriceSlotRegistry slots = new PriceSlotRegistry(2);
    private final Order order = new Order();
    private PriceCollarPolicy policy;

    @BeforeEach
    void setUp() {
        prices.writeQuote(slots.register(LISTING_ID), 39 * CENT, 41 * CENT); // mark 40¢
        policy = new PriceCollarPolicy(prices, slots, 10 * CENT);
    }

    @Test
    void aBuyMoreThanTheCollarAboveTheMarkIsRejected() {
        assertTrue(violated(Side.Bid, 51 * CENT));
    }

    @Test
    void aBuyAtTheEdgeOfTheCollarPasses() {
        assertFalse(violated(Side.Bid, 50 * CENT));
    }

    @Test
    void aPassiveBidFarBelowTheMarkPasses() {
        assertFalse(violated(Side.Bid, 1 * CENT));
    }

    @Test
    void aSellMoreThanTheCollarBelowTheMarkIsRejected() {
        assertTrue(violated(Side.Ask, 29 * CENT));
    }

    @Test
    void aSellAtTheEdgeOfTheCollarPasses() {
        assertFalse(violated(Side.Ask, 30 * CENT));
    }

    @Test
    void aPassiveAskFarAboveTheMarkPasses() {
        assertFalse(violated(Side.Ask, 99 * CENT));
    }

    @Test
    void aMarketOrderHasNoPriceToCollar() {
        order.encoder.side(Side.Bid).orderType(OrderType.MARKET).price(OrderDecoder.priceNullValue());
        assertFalse(policy.isViolated(0, LISTING_ID, order, positions, orders));
    }

    @Test
    void aListingWithoutAPriceSlotPasses() {
        order.encoder.side(Side.Bid).price(99 * CENT);
        assertFalse(policy.isViolated(0, LISTING_ID + 1, order, positions, orders));
    }

    @Test
    void aListingWithNoMarkYetPasses() {
        slots.register(LISTING_ID + 1);
        order.encoder.side(Side.Bid).price(99 * CENT);
        assertFalse(policy.isViolated(0, LISTING_ID + 1, order, positions, orders));
    }

    @Test
    void reconfigureSetsTheWidth() {
        policy.reconfigure(new ViewString("{\"maxDeviation\": " + 20 * CENT + "}"));
        assertFalse(violated(Side.Bid, 60 * CENT));
        assertTrue(violated(Side.Bid, 61 * CENT));
    }

    private boolean violated(final Side side, final long price) {
        order.encoder.side(side).orderType(OrderType.LIMIT).price(price).size(1);
        return policy.isViolated(0, LISTING_ID, order, positions, orders);
    }
}
