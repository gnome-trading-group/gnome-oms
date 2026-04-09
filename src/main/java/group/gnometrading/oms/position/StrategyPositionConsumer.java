package group.gnometrading.oms.position;

@FunctionalInterface
public interface StrategyPositionConsumer {
    void accept(int strategyId, int listingId, Position position);
}
