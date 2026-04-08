package group.gnometrading.oms.risk;

final class MarketPolicyGroup {
    final MarketRiskPolicy[] policies;
    int count;

    MarketPolicyGroup(final int capacity) {
        this.policies = new MarketRiskPolicy[capacity];
    }
}
