# Design: MaintainMovementSpecsView

## Overview

Add a `MaintainMovementSpecsView` page for creating and editing movement specs. Navigation to this page is triggered from `MovementSpecsView` — clicking a row edits the selected spec, a "Create" button opens the form blank. Follows the exact pattern of `MaintainAccountView`.

## Form Fields

- `name` — text input
- `inAccountId` — select dropdown, options populated from `model.accounts`
- `outAccountId` — select dropdown, options populated from `model.accounts`
- `dataSourceJson` — textarea

Create vs Update is determined by whether `model.movementSpec.id.isEmpty`.

After a successful save, navigate back to `Page.MovementSpecs`.

## Changes to MovementSpecsView

- Add `accounts: Seq[AccountPayload]` to `Model` — populated when `AccountsFromBackend` arrives (the full list is already in scope at that point)
- Add `SelectMovementSpec(msa: Option[MovementSpecAndAccounts])` to `Msg` — `None` for Create, `Some(msa)` when clicking a row
- `SelectMovementSpec` emits `AppMsg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec)`
- Add a "Create" button to the view that emits `SelectMovementSpec(None)`

## Navigation Message

Add to `AppMsg`:
```scala
case NavigateToMaintainMovementSpec(
  fundFlow:     FundFlowPayload,
  accounts:     Seq[AccountPayload],
  movementSpec: Option[MovementSpecPayload]
)
```

Handle in `WebApp.update`: set `maintainMovementSpecModel = MaintainMovementSpecsView.Model(fundFlow, accounts, movementSpec)` and emit `NavigateTo(Page.MaintainMovementSpec)`.

## Global Model and Msg Wiring

- Add `MaintainMovementSpecsMsg(msg: MaintainMovementSpecsView.Msg)` to `AppMsg`
- Add `maintainMovementSpecModel: MaintainMovementSpecsView.Model` to global `Model` (default `MaintainMovementSpecsView.Model()`)
- Initialize in `Model.init`

## Page.MaintainMovementSpec

```
path: /components/maintain-movement-spec
render: m => MaintainMovementSpecsView(m.maintainMovementSpecModel)
update: delegates MaintainMovementSpecsMsg to MaintainMovementSpecsView.update
beforeEnter: PrettyLogger.info only
```

## MaintainMovementSpecsView

### Model
```scala
case class Model(
  fundFlow:     FundFlowPayload             = FundFlowPayload(),
  accounts:     Seq[AccountPayload]         = Seq(),
  movementSpec: Option[MovementSpecPayload] = None
)
```

### Msg
```scala
enum Msg:
  case FormUpdate(spec: MovementSpecPayload)
  case MovementSpecAction(spec: MovementSpecPayload, action: PayloadAction)
```

### update
- `FormUpdate(spec)` → `model.copy(movementSpec = Some(spec))`
- `MovementSpecAction(spec, Create)` → `HttpHelper.createMovementSpec(spec)`
- `MovementSpecAction(spec, Created)` → navigate back to `Page.MovementSpecs` (via `AppMsg.NavigateToMovementSpecs(model.fundFlow)`)
- `MovementSpecAction(spec, Update)` → `HttpHelper.updateMovementSpec(spec)`
- `MovementSpecAction(spec, Updated)` → navigate back to `Page.MovementSpecs`

### view
Card layout matching `MaintainAccountView`. Four fields: name (text), inAccountId (select), outAccountId (select), dataSourceJson (textarea). Save button emits `MovementSpecAction(spec, Create)` or `MovementSpecAction(spec, Update)` based on whether `id` is empty.

## HttpHelper Changes

Add `createMovementSpec` and `updateMovementSpec` functions, and `movementSpecDecoder(action: PayloadAction)`:

```
POST /api/v1/movement-spec        → createMovementSpec(spec)
PUT  /api/v1/movement-spec/:id    → updateMovementSpec(spec)
```

Decoder decodes `MovementSpecPayload` and emits `Msg.MaintainMovementSpecsMsg(MaintainMovementSpecsView.Msg.MovementSpecAction(spec, action))`.

## Error Handling

No additional error handling beyond the existing global `Msg.Error` pattern.
