package group.gnometrading.oms.risk;

/** Plays an operator resuming a latched strategy: the registry takes over the halt, then the operator clears it. */
public final class RiskEngineResume {

    private RiskEngineResume() {}

    public static void resume(final RiskEngine engine, final int strategyId) {
        final RiskEngineSnapshot held = new RiskEngineSnapshot();
        held.confirmedHalts.add(strategyId);
        engine.publishSnapshot(held);
        engine.applyChanges(NO_KILLS);
        engine.publishSnapshot(new RiskEngineSnapshot());
        engine.applyChanges(NO_KILLS);
    }

    private static final RiskEngine.KillHandler NO_KILLS = new RiskEngine.KillHandler() {
        @Override
        public void onEverythingKilled() {}

        @Override
        public void onStrategyKilled(final int strategyId) {}

        @Override
        public void onListingKilled(final int listingId) {}

        @Override
        public void onStrategyListingKilled(final int strategyId, final int listingId) {}
    };
}
