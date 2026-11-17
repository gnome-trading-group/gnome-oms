package group.gnometrading.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.TriggerKind;
import org.junit.jupiter.api.Test;

class OrderCauseTest {

    @Test
    void anActionTakenOnAnExecutionReportCarriesTheReportAndNoIntent() {
        final OrderCause cause = new OrderCause();
        final CancelOrder cancel = new CancelOrder();

        cause.ofExecutionReport(42L, 5_000L, 4_000L);
        cause.stamp(cancel.encoder);

        assertEquals(0L, cancel.decoder.intentSequence());
        assertEquals(42L, cancel.decoder.triggerSequence());
        assertEquals(TriggerKind.EXEC_REPORT, cancel.decoder.triggerKind());
        assertEquals(5_000L, cancel.decoder.triggerTimestamp());
        assertEquals(4_000L, cancel.decoder.triggerOriginTimestamp());
    }

    @Test
    void outsideAnyEventItIsTheOmsActingOnItsOwn() {
        final OrderCause cause = new OrderCause();
        cause.ofExecutionReport(42L, 5_000L, 4_000L);

        cause.ofRisk();

        assertEquals(TriggerKind.RISK, cause.triggerKind());
        assertEquals(0L, cause.triggerSequence());
        assertEquals(0L, cause.triggerTimestamp());
    }

    @Test
    void anOrderCarriesItsIntentsEpisodeAndAReportOrRiskActionNone() {
        final OrderCause cause = new OrderCause();
        final Intent intent = new Intent();
        intent.encoder.triggerKind(TriggerKind.MARKET_DATA).episodeId(812L);
        final Order order = new Order();

        cause.ofIntent(9L, intent.decoder);
        cause.stamp(order.encoder);
        assertEquals(812L, order.decoder.episodeId());

        final OrderCause queued = new OrderCause();
        queued.copyFrom(cause);
        assertEquals(812L, queued.episodeId(), "a queued intent keeps its episode");

        cause.ofExecutionReport(42L, 5_000L, 4_000L);
        cause.stamp(order.encoder);
        assertEquals(OrderDecoder.episodeIdNullValue(), order.decoder.episodeId());
    }
}
