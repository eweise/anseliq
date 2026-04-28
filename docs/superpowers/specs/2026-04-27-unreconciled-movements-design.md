# Unreconciled Movements Page — Design Spec

**Date:** 2026-04-27

## Overview

Add a new frontend page that displays unreconciled movements for a given `MovementSpec`. The page is reached by clicking "View Unreconciled" on a row in the `MovementSpecsView`. Account IDs in movements are resolved to names using the accounts already available in `MovementSpecsView.Model`.

---

## Architecture

The page follows the existing Tyrian (TEA) pattern used by all other pages in this codebase:

- A `View` object (`UnreconciledMovementsView`) with `Msg`, `Model`, `update`, and `apply`
- A `Page` object entry (`Page.UnreconciledMovements`)
- A route entry in `Route.ComponentDemo`
- A navigation message `NavigateToUnreconciledMovements` in the global `Msg`/`Model`

---

## Components

### `UnreconciledMovementsView.scala` (new)

**Model:**
```
movementSpec: MovementSpecPayload   — the spec whose movements are shown
accounts:     Seq[AccountPayload]   — used to resolve inAccountId / outAccountId to names
movements:    Seq[MovementPayload]  — fetched on page enter
```

**Msg:**
- `MovementsFromBackend(movements: Seq[MovementPayload])` — populates the movements list

**update:**
- `MovementsFromBackend` → copy movements into model

**apply (render):**
- Page heading: movement spec name + "Unreconciled Movements"
- Table columns: Reference ID | Amount | In Account | Out Account
- Account names resolved via `accounts.map(a => a.id.get -> a.name).toMap`; fall back to raw ID if not found
- A "Back" link button that emits `NavigateToMovementSpecs(movementSpec.fundFlowId)` — note: `MovementSpecPayload` does not carry `fundFlowId`, so the model also stores the `fundFlow: FundFlowPayload` passed in at navigation time

### `HttpHelper.scala` (modified)

Add:
- `fetchUnreconciledMovements(movementSpecId: String)` — GET `movements/:movementSpecID/unreconciled`
- `unreconciledMovementsDecoder` — parses `Seq[MovementPayload]` → `UnreconciledMovementsMsg(MovementsFromBackend(...))`

### `model.scala` (modified)

- Add `unreconciledMovementsModel: UnreconciledMovementsView.Model` field to `Model` (default `UnreconciledMovementsView.Model()`)
- Add `UnreconciledMovementsMsg(msg: UnreconciledMovementsView.Msg)` to `Msg`
- Add `NavigateToUnreconciledMovements(movementSpec: MovementSpecPayload, accounts: Seq[AccountPayload], fundFlow: FundFlowPayload)` to `Msg`

### `Page.scala` (modified)

Add `Page.UnreconciledMovements`:
- path: `/components/unreconciled-movements`
- render: `UnreconciledMovementsView(m.unreconciledMovementsModel)`
- `beforeEnter`: fetches unreconciled movements using the spec ID from the model
- `update`: delegates `UnreconciledMovementsMsg` to `UnreconciledMovementsView.update`

### `route.scala` (modified)

Add `"unreconciled-movements" => Page.UnreconciledMovements` to `Route.ComponentDemo`.

### `WebApp.scala` (modified)

Handle `NavigateToUnreconciledMovements(movementSpec, accounts, fundFlow)`:
- Set `unreconciledMovementsModel = UnreconciledMovementsView.Model(movementSpec, accounts, fundFlow)`
- Emit `NavigateTo(Page.UnreconciledMovements)`

### `MovementSpecsView.scala` (modified)

Add a "View Unreconciled" button to each table row. The button emits a new local `Msg.ViewUnreconciled(msa: MovementSpecAndAccounts)`. The `update` handler for this emits `AppMsg.NavigateToUnreconciledMovements(msa.spec, model.accounts, model.fundFlow)`.

---

## Data Flow

```
MovementSpecsView row click "View Unreconciled"
  → MovementSpecsView.Msg.ViewUnreconciled(msa)
  → AppMsg.NavigateToUnreconciledMovements(msa.spec, model.accounts, model.fundFlow)
  → WebApp sets unreconciledMovementsModel, emits NavigateTo(Page.UnreconciledMovements)
  → Page.UnreconciledMovements.beforeEnter fetches movements/:movementSpecID/unreconciled
  → UnreconciledMovementsView.Msg.MovementsFromBackend(movements)
  → render table with account names resolved from accounts map
```

---

## Error Handling

- Account ID not found in map: display raw ID as fallback (no crash)
- HTTP errors: use existing `Msg.Error` global handler (consistent with all other pages)

---

## Out of Scope

- Pagination or filtering of movements
- Sorting controls
- Reconciling movements directly from this page
