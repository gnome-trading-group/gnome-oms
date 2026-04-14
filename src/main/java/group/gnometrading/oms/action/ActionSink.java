package group.gnometrading.oms.action;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;

public interface ActionSink {

    void onNewOrder(Order order);

    void onCancel(CancelOrder cancel);

    void onModify(ModifyOrder modify);

    /** Called when the OMS generates a synthetic exec report (e.g. risk rejection) for the strategy. */
    default void onExecReport(OrderExecutionReport report) {}
}
