package group.gnometrading.oms.risk;

final class OrderPolicyGroup {
    final OrderRiskPolicy[] policies;
    int count;

    OrderPolicyGroup(final int capacity) {
        this.policies = new OrderRiskPolicy[capacity];
    }
}
