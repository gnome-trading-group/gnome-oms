package group.gnometrading.oms.intent;

import group.gnometrading.oms.order.OmsCancelOrder;
import group.gnometrading.oms.order.OmsOrder;

public sealed interface OmsAction {
    record NewOrder(OmsOrder order) implements OmsAction {}
    record Cancel(OmsCancelOrder cancel) implements OmsAction {}
}
