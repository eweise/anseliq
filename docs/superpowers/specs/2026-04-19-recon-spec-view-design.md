# ReconSpecView Design

**Date:** 2026-04-19

## Overview

A new read-only list page `ReconSpecsView` that displays all `ReconciliationSpecPayload` records for a selected account. Navigation originates from `AccountsView`, where each account row gains a "Recon Specs" button. The view also loads movement specs for the account's fund flow so it can resolve `inMovementSpecId` and `outMovementSpecId` to human-readable names.

## Navigation Flow

1. User clicks "Recon Specs" button on an account row in `AccountsView`
2. `AccountsView` emits `AppMsg.NavigateToReconSpecs(account: AccountPayload)`
3. `WebApp` handles `NavigateToReconSpecs`: sets `reconSpecsModel` with the selected account + fund flow, fires two HTTP commands in parallel, then navigates to `Page.ReconSpecs`
4. `ReconSpecsView` renders once both responses have arrived

## Data Fetching

Two HTTP fetches triggered on navigation (parallel, order-independent):

| Fetch | Endpoint | Stored in model field |
|-------|----------|-----------------------|
| Recon specs for account | `GET /api/v1/account/:accountID/recon-specs` | `reconSpecs` |
| Movement specs for fund flow | `GET /api/v1/fund-flows/:fundFlowID/movement-specs` | `movementSpecs` |

The view resolves movement spec names by building `Map[String, String]` (id → name) from `movementSpecs`.

**Note on backend route:** The existing cask route uses `s"${HttpPath.account}/:accountID/${HttpPath.reconSpecs}"` which double-prefixes the path. The frontend constructs the URL directly as `HttpPath.url(s"${HttpPath.account}/$accountID/recon-specs")` to avoid this.

## View

Table with columns: **Description** | **Aging Limit** | **Relationship** | **In Movement Spec** | **Out Movement Spec**

`inMovementSpecId` and `outMovementSpecId` are looked up in the movement spec map; falls back to the raw ID string if not found.

## Architecture

Follows the established Tyrian (Elm-like) pattern used by `MovementSpecsView` and `AccountsView`.

### Files

| Action | File |
|--------|------|
| Create | `frontend/src/main/scala/onparity/view/pages/ReconSpecsView.scala` |
| Modify | `frontend/src/main/scala/onparity/view/pages/AccountsView.scala` |
| Modify | `frontend/src/main/scala/onparity/model/model.scala` |
| Modify | `frontend/src/main/scala/onparity/page/Page.scala` |
| Modify | `frontend/src/main/scala/onparity/main/WebApp.scala` |
| Modify | `frontend/src/main/scala/onparity/util/HttpHelper.scala` |

### ReconSpecsView internals

```
Msg:
  ReconSpecsFromBackend(specs: Seq[ReconciliationSpecPayload])
  MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])

Model:
  account:       AccountPayload              = AccountPayload()
  fundFlow:      FundFlowPayload             = FundFlowPayload()
  reconSpecs:    Seq[ReconciliationSpecPayload] = Seq()
  movementSpecs: Seq[MovementSpecPayload]    = Seq()
```

### model.scala additions

- `NavigateToReconSpecs(account: AccountPayload)` navigation message
- `ReconSpecsMsg(msg: ReconSpecsView.Msg)` wrapper
- `reconSpecsModel: ReconSpecsView.Model` field on `Model` (default `ReconSpecsView.Model()`)

### HttpHelper additions

- `fetchReconSpecsByAccountID(accountID: String)` — GET recon specs, decodes to `ReconSpecsMsg(ReconSpecsFromBackend(...))`
- `reconSpecsDecoder` — response decoder
- `reconSpecsMovementSpecsDecoder` — decodes movement specs response to `ReconSpecsMsg(MovementSpecsFromBackend(...))`