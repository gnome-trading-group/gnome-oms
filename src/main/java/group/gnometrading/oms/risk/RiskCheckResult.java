package group.gnometrading.oms.risk;

public sealed interface RiskCheckResult {

    record Approved() implements RiskCheckResult {}

    record Rejected(String policyName, String reason) implements RiskCheckResult {}
}
