package group.gnometrading.oms.risk;

final class OrderPolicyGroup {
    final OrderRiskPolicy[] policies;
    // The registry's id for each policy, so a rejection can name the one that refused; 0 when it has none.
    final int[] policyIds;
    int count;

    OrderPolicyGroup(final int capacity) {
        this.policies = new OrderRiskPolicy[capacity];
        this.policyIds = new int[capacity];
    }

    void add(final OrderRiskPolicy policy, final int policyId) {
        policies[count] = policy;
        policyIds[count] = policyId;
        count++;
    }
}
