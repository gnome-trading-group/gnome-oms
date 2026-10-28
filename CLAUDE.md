# CLAUDE.md

gnome-oms is the order management system library (`group.gnometrading:gnome-oms`). It runs inside a strategy
session's gnome-orchestrator JVM (and inside gnome-backtest, which drives `OmsAgent.doWork()` by hand). It turns
strategy `Intent`s into orders, tracks order state and positions, runs pre-trade and market risk checks, enforces
kill switches and halts, and writes a fills/orders ledger to the registry.

## Commands

- `mvn verify` - full build: spotless check + checkstyle (strict config) at `validate`, then all tests. This is what CI runs.
- `mvn spotless:apply` - fix formatting (palantir-java-format) before committing; spotless check fails the build otherwise.
- `mvn test -Dtest=group.gnometrading.oms.position.PositionTest` - one test class; always use the full class name.
- `mvn test -Dtest=group.gnometrading.oms.OmsAllocationTest` - allocation tests (`@Tag("allocation")`) run in a
  separate surefire execution, one fresh JVM per class, because Mockito's inline mock maker makes even real
  `SecurityMaster` instances allocate once anything in the JVM mocks it.
- `OmsLatencyBenchmarkTest` prints per-pass and intent latency numbers instead of asserting; compare before/after runs.
- Dependencies come from GitHub Packages (`maven.pkg.github.com/gnome-trading-group/*`); CI uses `-s settings.xml`
  with `GITHUB_ACTOR`/`GITHUB_TOKEN`; locally, put a GitHub token with `read:packages` in `~/.m2/settings.xml`.

## Layout

All code is under `src/main/java/group/gnometrading/oms/`; tests mirror it under `src/test/java/`.

- `OmsAgent` - the hot-path `GnomeAgent` (`ThreadProfile.HOT_PATH`). Polls the intent and exec-report
  `SequencedRingBuffer`s, publishes Order/Cancel/Modify to the order outbound buffer, and forwards exec reports
  (plus synthetic risk rejects) to the strategy only after positions are updated. `onClose` cancels everything.
- `OrderManagementSystem` - core logic: intent processing, exec reports, risk gating, kill sweeps, ledger health.
- `intent/` - `IntentResolver` (one per strategy) and `OrderSlot` (one order per side per listing, one in-flight
  action at a time). Read `docs/intent-processing.md` before changing this; it documents the slot state machine
  and FIX quantity semantics (wire size = total incl. filled; make size = desired resting).
- `risk/` - `RiskEngine` (hot-path checks against an immutable `RiskEngineSnapshot`), `RiskSyncAgent` (own thread:
  pulls policies from the registry, publishes snapshots, records latched halts), `policy/` (one class per
  `RiskPolicyType`), `CommittedCapital`, `MarketOrderPricing`.
- `position/` - `DefaultPositionTracker`, `SharedPositionBuffer` (off-heap, seqlock, 64-byte slots) and
  `LivePositionView` for strategy threads.
- `pnl/` - `PriceWriterAgent` writes top of book / last trade from market data into `SharedPriceBuffer` for marks.
- `ledger/` - `LedgerRing` (OMS thread publishes) and `LedgerAgent` (own thread, posts `/api/ledger/batch`).
- `state/` - `PooledOrderStateManager`, `TrackedOrder`: pooled order book of working orders.
- Tests: `OmsTestHarness` + scenario tests (`OmsScenarioTest`, `KillSwitchScenarioTest`, `ScopedPoliciesScenarioTest`,
  `OrderLimitsScenarioTest`), `OmsModelBasedTest`, `OmsInvariantTest`, and per-class unit tests.

## Conventions and invariants

- Build with Maven only, never Gradle. Java 17 (set by the `gnome-parent` pom from gnome-build-tools).
- The hot path (`OmsAgent.doWork` and everything it calls) must be zero-allocation: pre-allocated flyweights,
  Agrona primitive collections, no boxing, no `values()` (it copies). The allocation tests guard this; add
  coverage there when adding a hot-path branch. Background agents (`RiskSyncAgent`, `LedgerAgent`) may allocate.
- The OMS is a `GnomeAgent` that reads and writes `SequencedRingBuffer`s. Do not turn it into a passive
  callback library; the strategy only talks to it through ring buffers and shared buffers.
- No I/O on the OMS thread. Registry calls belong in `RiskSyncAgent` or `LedgerAgent`, which must never stop
  their thread: log, keep the last good state, retry.
- Libraries return null; consumers enforce business invariants (e.g. `RiskEngine.check` returns null when the order
  passes, `RiskPolicyType.fromString` returns null when unknown).
- Prices are scaled by 1e9 and sizes by 1e6 (`Statics.PRICE_SCALING_FACTOR` / `SIZE_SCALING_FACTOR` in gnome-schemas).
- Fail closed. A live session refuses all orders unless every listing has `MAX_COMMITTED_CAPITAL`,
  `MAX_TOTAL_PNL_LOSS`, `MAX_POSITION` and `MAX_CONSECUTIVE_REJECTS`; stale risk data refuses new orders; a
  policy that cannot be built kills its target instead of trading without it.
- `CommittedCapital` must match the registry's `committedCapital` in `gnome-registry/cdk/lambda/endpoints/ledger-math.ts`.
  Both test against `committed-capital-cases.json` (here in `src/test/resources`, there in `cdk/test/fixtures`);
  change all of them together.
- Integration and scenario tests use real components, not mocks. Mockito is fine for narrow unit tests.
- Code style: comments explain why, never what. Checkstyle uses `checkstyle-strict.xml` (hot-path rules).

## Operations

- No stacks or infrastructure of its own. It runs inside a strategy session's orchestrator JVM
  (`TradingOrchestrator` in gnome-orchestrator wires `OmsAgent`, `RiskSyncAgent`, `LedgerAgent`, `PriceWriterAgent`).
- Logs go to the session's orchestrator log, shipped to CloudWatch log group `/gnome/orchestrator/<region>`,
  stream `ec2/<instance-id>`. Key `LogMessage`s (defined in gnome-logging):
  - `ORDER_REJECTED_RISK_CHECK` - an order broke a limit; carries the registry policy id (0 = no registry policy,
    e.g. the order book is full). Strategy gets a synthetic REJECT with `RISK_LIMIT_EXCEEDED`.
  - `ORDER_REJECTED_HALTED` - logged once per halt start, not per order. Cause: 0 stale risk data,
    1 kill switch, 2 strategy latched, 3 unguarded (live session missing a required policy).
  - `ORDER_REJECTED_EXCHANGE_CONSTRAINTS` - listing spec rule broken (tick, min size), or a MARKET order with no price.
  - `STRATEGY_LATCHED` - market-risk breach; cause 0 market limit (e.g. PnL loss), 1 consecutive venue rejects.
    Orders in that scope are cancelled and it stays halted until an operator resumes it; PnL recovery does not unlatch.
  - `RISK_UNGUARDED` - live session missing a required policy on a listing; nothing trades until it is added.
  - `ORDER_REJECTED_MARKET_DATA_BAD` - the listing's book is flagged bad data (gateway gap, resync, lost feed; in
    backtests a collection gap), so new orders and modifies on it are refused with `MARKET_DATA_BAD` until a good
    book arrives. Logged once per bad spell. Resting orders and cancels are untouched; `PriceWriterAgent` writes no
    prices from bad records, so marks hold the last good book (`SharedPriceBuffer.badSince`).
  - `LEDGER_FAILING_HALTED`, `LEDGER_FENCED`, `LEDGER_WRITE_FAILED` - see ledger below.
  - "Risk policy refresh failed, keeping the last policies" (`UNKNOWN_ERROR`) - registry unreachable; if it lasts
    past the staleness window, orders are refused with halt cause 0.
- Kill switches are registry risk policies of type `KILL_SWITCH` (strategy, session, exchange or listing scope).
  `RiskSyncAgent` picks them up on its refresh; the OMS then cancels every order in scope and re-sweeps every
  second, since venues can refuse cancels. The refresh interval and staleness window are not set here: the
  orchestrator's `RiskModule` reads per-mode `refresh.interval.ms` and `stale.after.ms` properties.
- Latched halts are reported to the registry via `RiskMaster.requestHalt` (`POST /api/risk/halts`), retried until
  accepted, so operators can see and resume them.
- Ledger: `LedgerAgent` posts batches (max 40 events: fills, order opened/acked/modified/closed, gaps, refusal
  counts, plus marks) to `/api/ledger/batch`. 4xx (fenced or malformed) stops writing; other failures retry with
  backoff. If the ledger falls too far behind, the OMS latches every strategy (`CAUSE_LEDGER`) and cancels orders,
  so nothing fills unrecorded.
- Release: pushing to `main` runs gnome-build-tools' bump-and-deploy workflow (`mvn verify`, maven-release-plugin,
  publish to GitHub Packages). gnome-orchestrator and gnome-backtest pin the version in their `pom.xml`; bump and
  rebuild both to the same version, or gnomepy backtests will not pick up the change.
