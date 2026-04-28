# Reconciliations Page Design

## Overview

Add a new "Reconciliations" page that displays all reconciliation records for a given recon spec. Navigation to the page is from the Recon Specs view, where each row gets a "Reconciliations" button. Data comes from `GET /api/v1/recon-spec/:reconSpecID/reconciliations`.

## Data Shape

`ReconciliationPayload` (already defined in `common/`):

```scala
case class ReconciliationPayload(
  id:          String,
  reconSpecId: String,
  appliedRules: String,
  description: String,
  status:      String,
  createdAt:   OffsetDateTime,
  updatedAt:   OffsetDateTime
)
```

Table columns displayed: **Description, Status, Applied Rules, Created At, Updated At** (`id` and `reconSpecId` omitted — redundant in context).

## Architecture

Follows the same pattern as `UnreconciledMovementsView` (the closest existing analogue).

### New file: `ReconciliationsView.scala`

- `Model` holds:
  - `account: AccountPayload` — needed for the back-navigation to ReconSpecs
  - `reconSpec: ReconciliationSpecPayload` — context for the page title
  - `reconciliations: Seq[ReconciliationPayload]` — data to display
- Single `Msg`: `ReconciliationsFromBackend(reconciliations: Seq[ReconciliationPayload])`
- `update` handles `ReconciliationsFromBackend` by copying reconciliations into model
- `apply` renders a table with columns: Description, Status, Applied Rules, Created At, Updated At
- Back button emits `AppMsg.NavigateToReconSpecs(model.account)`
- Dates formatted using `ViewHelper.formatDateTime`

### `Page.scala` changes

Add `Page.Reconciliations`:
- `path = "/components/reconciliations"`
- `render` delegates to `ReconciliationsView(m.reconciliationsModel)`
- `update` dispatches `ReconciliationsMsg`
- `beforeEnter` fires `HttpHelper.fetchReconciliationsByReconSpecID(model.reconciliationsModel.reconSpec.id.get)`

### `HttpHelper.scala` changes

New function:
```scala
def fetchReconciliationsByReconSpecID(reconSpecId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken]
```
GET to `HttpPath.url(s"${HttpPath.reconSpec}/$reconSpecId/reconciliations")`.

New decoder in `ResponseDecoders`:
```scala
val reconciliationsDecoder: Decoder[Msg]
```
Parses `Seq[ReconciliationPayload]` and emits `Msg.ReconciliationsMsg(ReconciliationsView.Msg.ReconciliationsFromBackend(...))`.

### `model.scala` changes

Add to `Msg` enum:
- `ReconciliationsMsg(msg: ReconciliationsView.Msg)`
- `NavigateToReconciliations(reconSpec: ReconciliationSpecPayload, account: AccountPayload)`

Add to `Model` case class:
- `reconciliationsModel: ReconciliationsView.Model = ReconciliationsView.Model()`

Add to `Model.init`:
- `reconciliationsModel = ReconciliationsView.Model()`

### `WebApp.scala` changes

Handle `NavigateToReconciliations(reconSpec, account)`:
- Set `reconciliationsModel = ReconciliationsView.Model(account = account, reconSpec = reconSpec)`
- Emit `Msg.NavigateTo(Page.Reconciliations)`

### `ReconSpecsView.scala` changes

Add `Msg.ViewReconciliations(reconSpec: ReconciliationSpecPayload)` to `ReconSpecsView.Msg`.

Handle it in `update`: emit `AppMsg.NavigateToReconciliations(reconSpec, model.account)`.

Add a "Reconciliations" text button to each row's action div:
```scala
Buttons.textButton("Reconciliations", AppMsg.ReconSpecsMsg(Msg.ViewReconciliations(spec)))
```

## Scope

No backend changes required — the endpoint already exists.
