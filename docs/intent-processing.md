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

**Quantities.** A make size is the *resting* quantity the strategy wants working, not the
order's total. On the wire the OMS follows FIX: an `Order` or `ModifyOrder` size is the order
quantity, the order's total size including what has already filled, and exec reports carry the
cumulative quantity and leaves (leaves = order quantity − cumulative). The OMS translates: a
modify carries *desired resting + filled so far*. Keeping the wire quantity total is what keeps a
modify correct when fills land while it is in flight.

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
- On a **CANCEL_REJECT**: the cancel or modify was rejected by the exchange; the slot reverts
  to LIVE and the queued intent fires. The exception is a refused cancel on a venue without
  native modify (see below): its queued intent is dropped, and the strategy's next intent retries.

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
| LIVE            | Same price and resting size as what is working → nothing. Different → modify (order qty = size + filled) → PENDING_MODIFY; on a venue without native modify, queue (price, size) and cancel → PENDING_CANCEL. | Cancel → PENDING_CANCEL |
| PENDING_MODIFY  | Queue intent (price, size)                                | Queue cancel intent (0, 0)           |
| PENDING_CANCEL  | Queue intent (price, size)                                | Queue cancel intent (0, 0)           |

When the slot is LIVE and a modify is warranted, `RiskCheckingSink.onModify` intercepts
the modify before it reaches the action sink. If it passes risk checks, position leaves
are updated immediately (remove the old leaves, add the new ones: order qty − filled) and
`TrackedOrder` is updated. If it fails, a synthetic `CANCEL_REJECT` is published and then
injected back into the resolver, which fires any queued intent.

Because the comparison is against what is still resting, a partial fill that leaves less
working than the strategy asked for is topped back up the next time the strategy expresses that
intent: with 3 of 10 filled, an unchanged intent for 10 sends a modify to order qty 13.

### Exchange constraints

Before risk checks, every new order and modify (including a cancel-then-new replacement) is checked
against the listing's `ListingSpec` from the security master. A zero field is not checked, and a
listing with no spec passes everything.

| Rule | Check | Reject reason |
|---|---|---|
| Lot size | size (a modify's: the new leaves) is a multiple of `lotSize` | `INVALID_SIZE` |
| Tick size | price is a multiple of `tickSize`; market orders have no price and skip it | `INVALID_PRICE` |
| Min notional | price × size ≥ `minNotional`; a market order uses the last trade price | `INVALID_SIZE` |

The OMS does not round prices: strategies must send prices on the tick, and an off-tick intent is
rejected like any other constraint failure. `tickSize` is the increment the venue accepts everywhere
in its price range, so a price on it is never refused by the venue.

### Venues without native modify

Some venues cannot change a working order (Polymarket's CLOB: orders are signed and immutable).
`VenueCapabilities.supportsNativeModify(exchangeCode)` says which venues can; each slot asks once,
when it is created, via the listing's exchange. Unknown venues are treated as unable, since the
fallback is correct everywhere.

On those venues, where a modify would be sent the resolver instead queues the target (price,
resting size) and cancels the order. When the `CANCEL` arrives, the existing CANCEL branch submits
the target as a new order with a new client OID. The new order has nothing filled, so its size is
simply the resting size the strategy wants, whatever filled before the cancel landed. It is an
ordinary new order, so risk and exchange constraints apply to it. There is a gap with no order
working between the cancel and the new order's acknowledgement.

If the venue refuses the cancel, the slot returns to LIVE and the queued target is dropped rather
than retried straight away. Retrying would re-send the cancel, and a venue that keeps refusing
(e.g. a minimum order age) would turn that into a tight loop. The strategy's next intent retries.

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
2. **Accept**: `positionTracker.removeStrategyLeaves(old leaves)` then
   `positionTracker.addStrategyLeaves(new leaves)`, where new leaves = the modify's order qty −
   filled. Risk and exchange constraints are evaluated on those new leaves. Both firm and strategy positions are
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
immediately (old leaves removed, new leaves added) and `TrackedOrder.leavesQty` is set to the
new order qty less what has filled. The exchange later sends a NEW ack for the modified order. That ack does **not**
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
