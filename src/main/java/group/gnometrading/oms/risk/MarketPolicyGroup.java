package group.gnometrading.oms.risk;

final class MarketPolicyGroup {
    final MarketRiskPolicy[] policies;
    // The registry's id for each policy, so a breach can name the one that tripped; 0 when it has none.
    final int[] policyIds;
    int count;

    MarketPolicyGroup(final int capacity) {
        this.policies = new MarketRiskPolicy[capacity];
        this.policyIds = new int[capacity];
    }

    void add(final MarketRiskPolicy policy, final int policyId) {
        policies[count] = policy;
        policyIds[count] = policyId;
        count++;
    }
}
