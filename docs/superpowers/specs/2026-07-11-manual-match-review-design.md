# Manual Match / Review Path Design

## Overview

Today, movement matching is fully automatic (`InMemoryReconciler`): a `ReconciliationSpec` defines a rule, and matching runs unattended on a schedule/event trigger. If the rule can't match an item (wrong cardinality, missing data, one-off exception), the movement stays unreconciled forever with no way to resolve it — `UnreconciledMovementsPage` and `ReconciliationsPage` are read-only reports.

This adds a manual path with three operations, reachable from the existing recon-spec-scoped pages:

1. **Manual match** — a user selects any number of unreconciled "in" movements and any number of unreconciled "out" movements and force-creates a `Reconciliation` from them.
2. **Unmatch (void)** — undo a match (manual or automated), returning its movements to the unreconciled pool.
3. **Write off** — close an `InProgress` reconciliation whose residual won't reach zero, recording a reason.

Attribution ("who did this") is explicitly out of scope — there's no authenticated user wired into the live Cask backend (`Routes.scala`/`App`) despite the `User` model existing, so adding real attribution here would mean building auth as a prerequisite. Manual actions are recorded (what happened, when) but not attributed to a person in this iteration.

## Data Model Changes

### `Reconciliation` (`backend/src/main/scala/perfectpair/domain/model/Reconciliation.scala`)

```scala
enum ReconciliationStatus derives DbCodec:
  case InProgress, Final, Voided   // Voided is new

case class Reconciliation(
  @Id id:        ID                     = createID(),
  specId:        ID,
  appliedRules:  String                 = "",   // "manual" for manually-created reconciliations
  description:   String                 = "",
  var status:    ReconciliationStatus   = Final,
  var balance:   Money                  = 0,
  var adjustmentReason: Option[String]  = None,  // new — set only by write-off
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec
```

No new column is needed to mark "manual" origin — reuse `appliedRules`, which already records what produced an automated match; manual matches set it to the literal string `"manual"`.

### Schema (`backend/db/schema.sql`)

Add one nullable column to `recon.reconciliation`:
```sql
adjustment_reason text
```
`status` is stored as plain `text`, not a Postgres enum type, so the new `Voided` value needs no separate migration.

### Why Voided instead of delete, and why write-off doesn't touch balances

Movements move fully from `Account.unreconciledBalance` into `reconciledBalance` the moment they're linked to *any* `Reconciliation` — the residual `Reconciliation.balance` is bookkeeping for "this group doesn't net to zero yet," not money still sitting unreconciled at the account level. Consequences:

- **Write-off** only flips `status → Final` and records `adjustmentReason`. The linked movements' contribution to `reconciledBalance` is already correct and doesn't change.
- **Unmatch/void** is the one that reverses account balances, since it's undoing the link. It sets `status = Voided` rather than deleting the row, both to preserve audit history and to avoid the existing `movement.reconciliation_id ON DELETE CASCADE` FK, which would delete the movements themselves if a `Reconciliation` row were ever deleted. (That FK is a latent risk independent of this feature; not addressed here since this design never deletes a `Reconciliation` row.)

### New domain events (`backend/src/main/scala/perfectpair/domain/model/Event.scala`)

Following the existing `MoneyMovementsIngested` / `AccountReconciled` / `NewFileDetected` pattern, so these actions show up in the existing Events viewer for free:

```scala
case class ReconciliationManuallyMatched(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event
case class ReconciliationVoided(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event
case class ReconciliationWrittenOff(id: ID = UUID.randomUUID(), reconciliationID: ID, reason: String) extends Event
```

## Backend Service: `ManualReconciler`

New class `backend/src/main/scala/perfectpair/domain/service/ManualReconciler.scala`, parallel to `InMemoryReconciler`, using the same repos (`movementRepo`, `accountRepo`, `reconRepo`, `reconSpecRepo`, `eventStore`).

Extract the private `signedAmount(movement, accountID)` helper currently duplicated implicitly in `InMemoryReconciler` into a shared spot (a method on `Movement`) so both classes use identical logic instead of drifting.

### `matchMovements(reconSpecId: ID, inMovementIds: Seq[ID], outMovementIds: Seq[ID], description: String): Reconciliation`

1. Load the `ReconciliationSpec` (gives `accountId`, `inMovementSpecId`, `outMovementSpecId`).
2. Load each movement by ID. Validate:
   - `inMovementIds` non-empty and `outMovementIds` non-empty.
   - Every "in" movement has `movementSpecId == reconSpec.inMovementSpecId`; every "out" movement has `movementSpecId == reconSpec.outMovementSpecId`.
   - Every selected movement currently has `reconciliationId == None`.
   - Any violation throws a domain error (mapped to a 4xx response) — this is the guard against double-matching / stale selections from a race with another user or the automated reconciler.
3. `balance = sum(inAmounts) - sum(outAmounts)`; `status = if balance == 0 then Final else InProgress`.
4. Insert `Reconciliation(specId = reconSpecId, description = description, appliedRules = "manual", status, balance)`.
5. Stamp `reconciliationId` on every selected movement, persist each.
6. Sum `signedAmount(movement, accountId)` over all selected movements into `reconciledDelta`; `account.reconciledBalance += reconciledDelta`, `account.unreconciledBalance -= reconciledDelta`; persist account.
7. Publish `ReconciliationManuallyMatched`.

### `unmatch(reconciliationId: ID): Unit`

1. Load `Reconciliation`; error if not found or already `Voided`.
2. Load its movements via existing `MovementRepo.findByReconciliationID`.
3. Null out `reconciliationId` on each movement, persist.
4. Reverse the balance move on the account (`reconciledBalance -=`, `unreconciledBalance +=` the same `signedAmount` sum as in match step 6).
5. Set `status = Voided`; persist. Leave `balance`/`description` as the historical record.
6. Publish `ReconciliationVoided`.

### `writeOff(reconciliationId: ID, reason: String): Unit`

1. Load `Reconciliation`; error unless `status == InProgress`.
2. Set `adjustmentReason = Some(reason)`, `status = Final`; persist.
3. Publish `ReconciliationWrittenOff`.

All three run inside the existing per-request `transact(transactor)` wrapper in `Routes.scala`, so each is atomic. No extra locking is introduced for this scale.

One knock-on effect worth naming: `InMemoryReconciler`'s automated `OneToMany` Phase 1 re-opens reconciliations via `reconRepo.findInProgressBySpecId`. Once write-off flips a reconciliation to `Final`, the automated reconciler correctly stops trying to top it up — no extra guard needed there.

## REST Endpoints

New `HttpPath.reconciliation` (singular) constant in `common/src/main/scala/perfectpair/common/http/http.scala`, alongside the existing plural `reconciliations`:
```scala
val reconciliation = s"$prefix/reconciliation"
```

New routes in `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`:

```scala
@cask.route(s"${HttpPath.reconSpec}/:id/manual-match", methods = HttpCmd.POST)
def manualMatch(id: String, request: cask.Request): String =
  val payload = read[ManualMatchRequest](request.text())
  transact(transactor):
    write(Payload.reconFromDomain(
      manualReconciler.matchMovements(
        UUID.fromString(id),
        payload.inMovementIds.map(UUID.fromString),
        payload.outMovementIds.map(UUID.fromString),
        payload.description
      )
    ))

@cask.route(s"${HttpPath.reconciliation}/:id/unmatch", methods = HttpCmd.POST)
def unmatchReconciliation(id: String): String =
  transact(transactor):
    manualReconciler.unmatch(UUID.fromString(id))
  write("voided")

@cask.route(s"${HttpPath.reconciliation}/:id/write-off", methods = HttpCmd.POST)
def writeOffReconciliation(id: String, request: cask.Request): String =
  val payload = read[WriteOffRequest](request.text())
  transact(transactor):
    manualReconciler.writeOff(UUID.fromString(id), payload.reason)
  write("written-off")
```

`manualReconciler: ManualReconciler` is added to `App`'s `using` constructor params in `Routes.scala` and wired in `Main.scala` where the other services are instantiated.

New payload types in `common/src/main/scala/perfectpair/common/model/Payload.scala`:
```scala
case class ManualMatchRequest(
  inMovementIds:  Seq[String],
  outMovementIds: Seq[String],
  description:    String = "Manual match"
) derives ReadWriter

case class WriteOffRequest(reason: String) derives ReadWriter
```

`ReconciliationPayload` gains the two fields the review UI needs (currently missing entirely):
```scala
case class ReconciliationPayload(
  id:               String,
  reconSpecId:      String,
  appliedRules:     String,
  description:      String,
  status:           String,
  balance:          Money,
  adjustmentReason: Option[String],
  var createdAt:    OffsetDateTime,
  var updatedAt:    OffsetDateTime
) derives ReadWriter
```
`Payload.reconFromDomain` updated to populate the two new fields.

No new endpoint is needed to fetch both sides of a spec's unreconciled movements — the frontend calls the existing `GET /api/v1/movements/:movementSpecID/unreconciled` twice (once for `inMovementSpecId`, once for `outMovementSpecId`).

## Frontend UI

### New page: `ManualMatchPage` (`frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala`)

Reached via a new "Manual Match" button on `ReconciliationsPage`. Reads `AppState.editReconSpec` for context (`inMovementSpecId`, `outMovementSpecId`, `id`), the same way `ReconciliationsPage` already does — no new global state needed.

- Two side-by-side panels, "In Movements" / "Out Movements", each fetched via `GET /movements/:movementSpecID/unreconciled` for the respective spec side. Each row (built on `Table.create`): a checkbox, reference ID, amount, a compact properties preview.
- Live summary bar above the match button: `Selected In: n ($X)  Selected Out: m ($Y)  Diff: $Z`. Green with "will close as Final" when `Z == 0`; amber with "will save as InProgress — can be topped up or written off later" otherwise. Pure client-side signal over the two selection sets.
- Description text field, default `"Manual match"`.
- "Match Selected" button, disabled until at least one row is checked on each side. On click: `POST /recon-spec/:id/manual-match`. On success: clear selections, refetch both unreconciled lists so matched rows disappear.
- "Back" button → `Nav.toReconciliations(accountId)`.

### `ReconciliationsPage` extension

Reused rather than building a separate review page — it already lists everything by spec.

- New columns: `Balance`, `Adjustment Reason` (from the new `ReconciliationPayload` fields).
- New `Actions` column, contents depend on `status`:
  - `InProgress`: **Write Off** (click reveals an inline reason text field + confirm button, tracked via a small per-row `Var[Option[String]]` for "which row is entering a reason") and **Unmatch**.
  - `Final`: **Unmatch** only.
  - `Voided`: no actions; row rendered visually muted (`opacity-60`) so history stays visible but reads as closed.
- Both actions call their endpoint via `ApiClient`, then refetch the reconciliations list on success.
- New "Manual Match" button next to the existing "Back to Recon Specs" button → `Nav.toManualMatch(accountId)`.

### Routing

`Nav.toManualMatch(accountId: String): Unit = push(s"/components/manual-match/$accountId")`, following the exact pattern of `Nav.toReconciliations`. Register the route → `ManualMatchPage` mapping the same place `toReconciliations` / `ReconciliationsPage` is registered.

Deliberate omission: unmatching from `ReconciliationsPage` doesn't push any update to `ManualMatchPage` — different page, no shared live state. Freed-up movements simply reappear next time someone opens Manual Match or the unreconciled report. Not worth cross-page reactivity for v1.

## Error Handling

- **Stale/already-reconciled selection** (`matchMovements`): a movement selected in the UI gets matched by someone else (or the automated reconciler) before the user hits "Match Selected". Backend validation in step 2 catches this inside the same transaction and returns an error; frontend surfaces it via the existing `AppState.globalError` pattern and refetches both unreconciled lists so the UI reflects reality.
- **Cross-spec selection**: a movement not belonging to the spec's declared `inMovementSpecId`/`outMovementSpecId` is rejected the same way — shouldn't be reachable through the UI (lists are fetched per-spec) but guarded server-side regardless, since the endpoint takes raw IDs.
- **Double-unmatch / write-off in wrong state**: `unmatch` on an already-`Voided` reconciliation and `writeOff` on anything but `InProgress` both throw domain errors rather than silently no-op-ing, surfaced the same way.
- **Empty selection**: "Match Selected" is disabled client-side until both sides are non-empty; the backend also validates this so the constraint holds even if hit directly.

## Testing

Following this repo's TDD convention (munit + `rollBackOnly` for DB isolation, per `backend/CLAUDE.md`), new suites mirror the structure of `InmemoryReconcilerSuite`:

- **`ManualReconcilerSuite`**:
  - `matchMovements` with a single in/single out movement nets to zero → `Final`, `balance == 0`, both movements linked, account `reconciledBalance`/`unreconciledBalance` updated correctly.
  - `matchMovements` with N in / M out movements summing to a non-zero residual → `InProgress`, `balance` reflects the residual.
  - `matchMovements` rejects a movement that's already reconciled.
  - `matchMovements` rejects a movement from the wrong `MovementSpec`.
  - `matchMovements` rejects empty selection on either side.
  - `unmatch` reverses account balances and returns movements to unreconciled (`reconciliationId == None`).
  - `unmatch` on an already-`Voided` reconciliation errors.
  - `writeOff` on an `InProgress` reconciliation sets `Final` + `adjustmentReason`, leaves account balances untouched.
  - `writeOff` on a `Final` or `Voided` reconciliation errors.
  - After `writeOff`, a subsequent automated `InMemoryReconciler.reconcileAccount` run no longer tries to top up that reconciliation (regression guard for the Phase-1 interaction noted above).
- Route-level coverage can stay light (existing route tests in this repo are sparse); the service-level suite above carries the real behavioral coverage.

## Scope / Explicit Non-Goals

- No user attribution (see Overview).
- No changes to the automated `InMemoryReconciler` matching logic itself.
- No fix to the `movement.reconciliation_id ON DELETE CASCADE` FK — irrelevant here since `Reconciliation` rows are never deleted, only voided.
- No cross-page live refresh between `ManualMatchPage` and `ReconciliationsPage`.
