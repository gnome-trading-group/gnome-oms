package group.gnometrading.oms;

import group.gnometrading.oms.intent.ClientOidGenerator;
import group.gnometrading.oms.intent.Intent;
import group.gnometrading.oms.intent.IntentResolver;
import group.gnometrading.oms.intent.OmsAction;
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
import java.util.logging.Logger;

public class OrderManagementSystem {

  private static final Logger logger = Logger.getLogger(OrderManagementSystem.class.getName());

  private final OrderStateManager orderStateManager;
  private final PositionTracker positionTracker;
  private final RiskEngine riskEngine;
  private final IntentResolver intentResolver;

  public OrderManagementSystem(RiskEngine riskEngine, ClientOidGenerator oidGenerator) {
    this(new DefaultOrderStateManager(), new DefaultPositionTracker(), riskEngine, oidGenerator);
  }

  public OrderManagementSystem(
      OrderStateManager orderStateManager,
      PositionTracker positionTracker,
      RiskEngine riskEngine,
      ClientOidGenerator oidGenerator) {
    this.orderStateManager = orderStateManager;
    this.positionTracker = positionTracker;
    this.riskEngine = riskEngine;
    this.intentResolver = new IntentResolver(orderStateManager, oidGenerator);
  }

  // --- Intent processing ---

  public void processIntent(Intent intent, Consumer<OmsAction> approvedActionConsumer) {
    intentResolver.resolve(intent, action -> {
      if (action instanceof OmsAction.NewOrder newOrder) {
        RiskCheckResult result = validateOrder(newOrder.order());
        if (result instanceof RiskCheckResult.Approved) {
          onOrderAccepted(newOrder.order());
          approvedActionConsumer.accept(action);
        } else if (result instanceof RiskCheckResult.Rejected rejected) {
          logger.warning("Order " + newOrder.order().clientOid() + " rejected by "
              + rejected.policyName() + ": " + rejected.reason());
        }
      } else if (action instanceof OmsAction.Cancel) {
        approvedActionConsumer.accept(action);
      }
    });
  }

  public void processIntents(Intent[] intents, int count, Consumer<OmsAction> approvedActionConsumer) {
    for (int i = 0; i < count; i++) {
      processIntent(intents[i], approvedActionConsumer);
    }
  }

  // --- Direct order management ---

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

  public IntentResolver getIntentResolver() {
    return intentResolver;
  }
}
