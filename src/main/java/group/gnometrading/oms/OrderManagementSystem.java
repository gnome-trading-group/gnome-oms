package group.gnometrading.oms;

import group.gnometrading.oms.order.OmsExecutionReport;
import group.gnometrading.oms.order.OmsOrder;
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

public class OrderManagementSystem {

  private final OrderStateManager orderStateManager;
  private final PositionTracker positionTracker;
  private final RiskEngine riskEngine;

  public OrderManagementSystem(RiskEngine riskEngine) {
    this(new DefaultOrderStateManager(), new DefaultPositionTracker(), riskEngine);
  }

  public OrderManagementSystem(
      OrderStateManager orderStateManager,
      PositionTracker positionTracker,
      RiskEngine riskEngine) {
    this.orderStateManager = orderStateManager;
    this.positionTracker = positionTracker;
    this.riskEngine = riskEngine;
  }

  public RiskCheckResult validateOrder(OmsOrder order) {
    return riskEngine.check(order, positionTracker, orderStateManager);
  }

  public void onOrderAccepted(OmsOrder order) {
    orderStateManager.trackOrder(order);
  }

  public void processExecutionReport(OmsExecutionReport report) {
    TrackedOrder tracked = orderStateManager.applyExecutionReport(report);

    if (tracked != null && (report.execType() == ExecType.FILL || report.execType() == ExecType.PARTIAL_FILL)) {
      positionTracker.applyFill(
          report.exchangeId(),
          report.securityId(),
          tracked.getSide(),
          report.filledQty(),
          report.fillPrice(),
          report.fee());
    }
  }

  // --- Query methods ---

  public Position getPosition(int exchangeId, long securityId) {
    return positionTracker.getPosition(exchangeId, securityId);
  }

  public void forEachPosition(Consumer<Position> consumer) {
    positionTracker.forEachPosition(consumer);
  }

  public TrackedOrder getOrder(String clientOid) {
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
}
