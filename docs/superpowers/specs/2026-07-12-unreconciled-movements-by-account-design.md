# Unreconciled Movements by Account — Design Spec

**Date:** 2026-07-12

## Overview

Add a page that shows all unreconciled movements for a reconciliation account, reached by
clicking the "Unreconciled Count" value on `FundFlowPage`'s Accounts tab.

An earlier page, `UnreconciledMovementsPage`, already exists but is unwired (nothing
navigates to it) and was built for the old Tyrian-based UI around a single `MovementSpec`
(see `docs/superpowers/specs/2026-04-27-unreconciled-movements-design.md`, now stale after
the Laminar migration). This spec repurposes that page to be account-scoped instead, since
that's what the "Unreconciled Count" link needs and there's no other consumer of the
spec-scoped version.

The backend already has an unused repo method, `MovementRepo.findUnReconciledByAccount`,
that returns movements from both directions (in and out) for an account — matching exactly
what `countUnreconciledByAccount` (used to compute "Unreconciled Count") counts. It just
needs an HTTP route.

---

## Backend

### `Routes.scala` (modified)

Add:
```scala
@cask.route(s"${HttpPath.account}/:id/unreconciled-movements", methods = HttpCmd.GET)
def unreconciledMovementsByAccount(id: String): String =
  transact(transactor):
    val movements = movementRepo.findUnReconciledByAccount(UUID.fromString(id))
    val payload   = movements.map(Payload.movementfromDomainModel)
    write(payload)
```

No domain/port changes — `findUnReconciledByAccount` already exists on `MovementRepo` /
`MovementRepoLive` and is implemented; it's just never been called from an HTTP route.

---

## Frontend

### `UnreconciledMovementsPage.scala` (modified)

- Change `apply()` to `apply(accountId: String)`, matching the `ReconciliationsPage` /
  `ManualMatchPage` pattern of taking the account id as a route param.
- Drop the `MovementSpecPayload` / `AppState.editMovementSpec` dependency — no longer
  spec-scoped.
- Keep fetching accounts via `${HttpPath.fundFlows}/$ffId/accounts` (ffId taken from
  `AppState.activeFundFlow`, which is already populated whenever this page is reached from
  `FundFlowPage`) to build an id → name map, used both for the page heading (the clicked
  account's name) and for resolving `inAccountId` / `outAccountId` to names in the table,
  same fallback-to-raw-id behavior as today.
- Fetch movements via `${HttpPath.account}/$accountId/unreconciled-movements` instead of
  the old `${HttpPath.movements}/$specId/unreconciled`.
- Heading becomes `"<Account Name> — Unreconciled Movements"`.
- Table columns unchanged: Reference ID | Amount | In Account | Out Account.
- "Back" button unchanged (`Nav.back()`).

### `Nav.scala` (modified)

Replace:
```scala
def toUnreconciledMovements(): Unit = push("/components/unreconciled-movements")
```
with:
```scala
def toUnreconciledMovements(accountId: String): Unit =
  push(s"/components/accounts/$accountId/unreconciled-movements")
```

### `Routes.scala` (frontend, modified)

Replace:
```scala
path("unreconciled-movements")(UnreconciledMovementsPage())
```
with, grouped alongside the other `accounts / segment / ...` routes:
```scala
path("accounts" / segment / "unreconciled-movements")(accountId => UnreconciledMovementsPage(accountId))
```

### `FundFlowPage.scala` (modified)

In the Accounts tab table, the "Unreconciled Count" cell:
- When `account.isRecon` is true: render as a clickable element (right-aligned like today,
  styled as a text link — blue, underline on hover — not a full button) that calls
  `Nav.toUnreconciledMovements(accountId)` on click.
- When `account.isRecon` is false: unchanged plain `span` (count is always 0 for source
  accounts, same as the existing blank-out behavior for Reconciled/Unreconciled Balance
  columns).

---

## Data Flow

```
FundFlowPage Accounts tab, recon account row
  → click "Unreconciled Count" cell
  → Nav.toUnreconciledMovements(accountId)
  → route accounts/:id/unreconciled-movements → UnreconciledMovementsPage(accountId)
  → fetch fund-flows/:ffId/accounts (name resolution) + account/:accountId/unreconciled-movements
  → render heading "<Account Name> — Unreconciled Movements" and movements table
  → "Back" → Nav.back() returns to FundFlowPage
```

---

## Error Handling

- HTTP errors on either fetch: routed to `AppState.globalError`, consistent with all other
  pages.
- Account id not found in the name map: display raw id as fallback (unchanged behavior).

---

## Out of Scope

- Pagination or filtering of movements.
- Sorting controls.
- Reconciling movements directly from this page.
- Any change to the spec-scoped `movements/:movementSpecID/unreconciled` endpoint or its
  usage in `ManualMatchPage` — left as-is.
