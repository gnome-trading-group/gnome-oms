# Intent Processing

An `Intent` is the message a strategy sends to the OMS to express what it wants on the
market — not a specific order, but a desired state. The OMS translates that desire into
actual order actions (new order, modify, cancel) and keeps it reconciled as execution
reports flow back.

---

## 1. Intent Schema

An Intent carries two independent modes in a single message:

**Make fields** — desired resting limit orders on each side:

| Field      | Meaning                                         |
|------------|-------------------------------------------------|
| `bidPrice` | price of the desired resting bid; null = no bid |
| `bidSize`  | size of the desired resting bid; null = no bid  |
| `askPrice` | price of the desired resting ask; null = no ask |
| `askSize`  | size of the desired resting ask; null = no ask  |

**Take fields** — an aggressive IOC/market order to execute immediately:

| Field            | Meaning                                             |
|------------------|-----------------------------------------------------|
| `takeSize`       | qty to take; null = no take this intent             |
| `takeSide`       | `Bid` or `Ask`                                      |
| `takeOrderType`  | `MARKET` or `LIMIT`; null defaults to `MARKET`      |
| `takeLimitPrice` | limit price when `takeOrderType=LIMIT`; else unused |

Both halves are processed together on every `processIntent` call. A pure make intent
leaves take fields null; a pure take intent leaves bid/ask fields null (which, crucially,
maps to size=0 — see section 3).

---

## 2. The Slot Abstraction

For make orders, the OMS enforces a **one-order-per-side-per-listing** constraint through
`OrderSlot`. Each `IntentResolver` (one per strategy) maintains two `LongHashMap<OrderSlot>`
keyed by `listingId` — one map for bids, one for asks. A `listingId` uniquely identifies a
(exchange, security) pair, so the same security trading on two exchanges gets two independent
slots. At most one slot entry exists per listing, and each slot can hold at most one live order
at a time.

The slot's job is to sequence order lifecycle events so that only one in-flight action
exists at a time, and to buffer incoming intents that arrive while an action is pending.

### Slot State Machine

```
                  submitNew()
     EMPTY ─────────────────────────► PENDING_NEW
       ▲                                   │
       │                                   │ NEW ack (no queued)
       │                         onNewAcked()
       │                                   │
       │           emitModify()            ▼
       │   ┌──── PENDING_MODIFY ◄──────── LIVE ────► PENDING_CANCEL
       │   │        │                      ▲              │
       │   │        │ NEW ack              │              │ CANCEL ack /
       │   │        │ onModifyConfirmed()  │              │ REJECT / EXPIRE
       │   │        └──────────────────────┘              │
       │   │                                              │
       └───┴──────────────────────────────────────────────┘
            terminal (FILL / REJECT / EXPIRE from PENDING_NEW)
```

States:

- **EMPTY** — no active order; slot is ready to accept a new submission.
- **PENDING_NEW** — a new order has been sent to the exchange; awaiting the first exec report.
- **LIVE** — the exchange has acknowledged the order (`ExecType.NEW`). Modifies and cancels
  can now be sent.
- **PENDING_MODIFY** — a modify has been sent; awaiting the exchange's confirmation
  (`ExecType.NEW` with the new price/size) or rejection (`ExecType.CANCEL_REJECT`).
- **PENDING_CANCEL** — a cancel has been sent; awaiting `ExecType.CANCEL`, `REJECT`, or
  `EXPIRE`.

### Queued Intents

Only one in-flight action can exist per slot. If a new intent arrives while the slot is in
any pending state (`PENDING_NEW`, `PENDING_MODIFY`, `PENDING_CANCEL`), the intent is stored
as a queued intent (overwriting any previously stored one — only the latest matters).

The queued intent fires as soon as the slot transitions back to LIVE:

- On a **NEW ack**: if the slot was `PENDING_MODIFY`, it confirms the modify and moves to
  LIVE; otherwise it moves from `PENDING_NEW` to LIVE. Then the queued intent fires
  immediately — emitting a modify, cancel, or nothing depending on what was queued.
- On a **CANCEL_REJECT**: the cancel was rejected by the exchange; the slot reverts to LIVE
  and the queued intent fires.

If nothing is queued when the slot becomes LIVE, no action is taken.

---

## 3. Processing a Make Intent (`resolveSide`)

`IntentResolver.resolve` calls `resolveSide` twice — once for the bid, once for the ask —
with the intent's price and size for that side. Size=0 (including null mapped to 0) means
"I want no resting order on this side."

Decision table by current slot state and desired outcome:

| Slot State      | Wants order (`size > 0`)                                  | No order (`size == 0`)               |
|-----------------|-----------------------------------------------------------|--------------------------------------|
| EMPTY           | Submit new order → PENDING_NEW                            | Nothing                              |
| PENDING_NEW     | Queue intent (price, size)                                | Queue cancel intent (0, 0)           |
| LIVE            | Same price+size as active → nothing. Different → modify → PENDING_MODIFY. | Cancel → PENDING_CANCEL |
| PENDING_MODIFY  | Queue intent (price, size)                                | Queue cancel intent (0, 0)           |
| PENDING_CANCEL  | Queue intent (price, size)                                | Queue cancel intent (0, 0)           |

When the slot is LIVE and a modify is warranted, `RiskCheckingSink.onModify` intercepts
the modify before it reaches the action sink. If it passes risk checks, position leaves
are updated immediately (remove old size, add new size) and `TrackedOrder` is updated.
If it fails, a synthetic `CANCEL_REJECT` is injected back into the resolver, which then
fires any queued intent.

---

## 4. Processing a Take Intent (`resolveTake`)

A take intent goes through `resolveTake`, which bypasses the slot mechanism entirely:

1. Determine order type: `takeOrderType` == null → `MARKET`; else use as given.
2. Allocate a fresh OID from the shared counter.
3. Build a new order with `TimeInForce.IMMEDIATE_OR_CANCEL` and the chosen order type.
4. Send it through `RiskCheckingSink.onNewOrder` — same risk gate as make orders.
5. If accepted, the take order is tracked in the state manager and position leaves are
   updated, just like any other new order.

Take orders have **no slot**. When exec reports arrive for a take OID, the resolver's
`onExecutionReport` returns early (no slot has that OID as its active order). Position
tracking (`updatePositionTracking`) still runs before the resolver is called, so fills,
cancels, and rejects all update the position correctly without slot involvement.

### Side Effect on Make Orders

A take intent is expressed with null bid/ask fields. The null sentinel maps to size=0 in
`IntentResolver.resolve`, so `resolveSide(Bid, 0, 0)` and `resolveSide(Ask, 0, 0)` run
**before** `resolveTake`. Concretely:

- If the make slot is **LIVE**: a cancel is emitted immediately → slot moves to
  `PENDING_CANCEL`.
- If the make slot is **PENDING_NEW/MODIFY/CANCEL**: a cancel intent `(0, 0)` is queued,
  overwriting any previously queued make intent. When the slot next becomes LIVE, it will
  cancel instead of modify.

This means a take intent implicitly withdraws any resting make order on the same security.
The strategy's intent is "take aggressively now; do not leave a resting order behind."

---

## 5. Risk Checking (`RiskCheckingSink`)

The `RiskCheckingSink` wraps the outbound `ActionSink` and intercepts new orders and
modifies before they leave the OMS.

### New Orders

1. **Exchange constraints**: lot size and minimum notional checks from `ListingSpec`. Fail →
   synthetic `REJECT` injected into the resolver and forwarded to the caller; no position
   change.
2. **Risk engine**: configured per-strategy policies (max position, max order size, max
   notional, max PnL loss, etc.). Fail → same synthetic reject path.
3. **Accept**: `onOrderAccepted` is called — the order is tracked in `OrderStateManager`
   and `positionTracker.addStrategyLeaves` records the pending quantity on both the firm
   and strategy positions. Then the order is forwarded to the real action sink.

### Modifies

1. Same exchange constraint and risk engine checks (risk is re-evaluated against the new
   size).
2. **Accept**: `positionTracker.removeStrategyLeaves(old size)` then
   `positionTracker.addStrategyLeaves(new size)` — both firm and strategy positions are
   updated immediately, before the modify reaches the exchange. `TrackedOrder.modify` also
   updates the order's stored price and size.
3. **Fail**: a synthetic `CANCEL_REJECT` is injected into the resolver, which reverts the
   slot state and fires any queued intent.

---

## 6. Execution Report Feedback

`OrderManagementSystem.processExecutionReport` is the inbound path:

```
processExecutionReport(report, sink)
  │
  ├─ orderStateManager.applyExecutionReport(report)   // update TrackedOrder state
  │
  ├─ updatePositionTracking(report, tracked)           // update position
  │     FILL/PARTIAL_FILL → removeLeaves(filledQty) + applyFill(qty, price, fee)
  │     CANCEL/REJECT/EXPIRE → removeLeaves(leavesQtyBefore)
  │     NEW / CANCEL_REJECT → no change
  │
  ├─ forwardToResolver(report, tracked, sink)          // advance slot state machine
  │
  └─ if terminal → releaseOrder(tracked)
```

### Position Updates in Detail

`leavesQtyBefore` is captured from `TrackedOrder` **before** `applyExecutionReport` runs,
so it reflects the quantity that was live just before this report arrived.

- **FILL** (`leavesQty=0`): remove `filledQty` from leaves; apply fill to cost and PnL.
  Slot → EMPTY; queued intent **dropped** (a fill is terminal, strategy must re-express).
- **PARTIAL_FILL**: remove `filledQty` from leaves; apply fill. Slot stays live or pending;
  no slot state change in the resolver.
- **CANCEL / REJECT / EXPIRE**: remove `leavesQtyBefore` from leaves. Slot → EMPTY. If a
  queued intent exists with `size > 0`, a new order is submitted immediately (resubmit).
  If the queued intent is `(0, 0)` or absent, the slot stays EMPTY.
- **NEW ack**: no position change. Slot → LIVE. Queued intent fires if present.
- **CANCEL_REJECT**: no position change. Slot reverts from `PENDING_MODIFY` or
  `PENDING_CANCEL` to LIVE. Queued intent fires if present.

### `leavesQty` Accounting Through a Modify

When a modify is accepted by `RiskCheckingSink.onModify`, position leaves are updated
immediately (old size removed, new size added) and `TrackedOrder.leavesQty` is set to the
new size. The exchange later sends a NEW ack for the modified order. That ack does **not**
produce another position update — the leaves were already reconciled when the modify was
sent. If the modify is rejected (`CANCEL_REJECT`), `RiskCheckingSink` has not changed the
position, so nothing needs to be undone.

---

## 7. OID Assignment

All strategies within a single OMS instance share one monotonically increasing OID counter
(`OrderManagementSystem.oidCounter`). Each `IntentResolver` receives a `LongSupplier`
reference to `this::nextOid`. OIDs are allocated in the order actions are emitted:

1. Make bid (if a new order is submitted for the bid slot)
2. Make ask (if a new order is submitted for the ask slot)
3. Take order (if `takeSize` is non-null)

This ordering is fixed and must be mirrored exactly by any test model or replay harness
that tracks OIDs independently.

---

## 8. Summary of Invariants

- At most one active order exists per (strategy, security, side) at any time.
- A queued intent is always overwritten by the next intent; only the latest matters.
- A FILL always clears the queued intent. A resubmit only happens on CANCEL/REJECT/EXPIRE.
- Take orders have no slot; their position tracking is driven entirely by exec reports.
- A take intent implicitly cancels or queues a cancel for any live/pending make order on
  the same security, because null bid/ask sizes map to size=0.
- Modify leaves updates happen at send time (not ack time). The NEW ack for a modified
  order produces no position change.
- Position leaves use `Math.max(0, current - qty)` — they never go negative, guarding
  against out-of-order or duplicate reports.
