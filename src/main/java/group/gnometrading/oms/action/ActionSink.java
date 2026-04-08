package group.gnometrading.oms.action;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;

public interface ActionSink {

    void onNewOrder(Order order);

    void onCancel(CancelOrder cancel);

    void onModify(ModifyOrder modify);
}
