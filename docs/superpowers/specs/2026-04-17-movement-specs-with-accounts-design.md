# Design: MovementSpecsView with MovementSpecAndAccounts

## Overview

Change `MovementSpecsView` to fetch both movement specs and accounts for a FundFlow, combine them using `zipAccountsAndMovementSpecs`, and store the result as `Seq[MovementSpecAndAccounts]` in the model.

## Data Flow

Navigation triggers a sequential two-step fetch:

1. `Msg.NavigateToMovementSpecs(fundFlow)` fires `fetchMovementSpecsByFundFlowID`
2. `MovementSpecsView.Msg.MovementSpecsFromBackend(specs)` stores specs and fires `fetchAccountsByFundFlowID`
3. `MovementSpecsView.Msg.AccountsFromBackend(accounts)` calls `zipAccountsAndMovementSpecs(accounts, model.movementSpecs)` and stores the result in `movementSpecAndAccounts`

## Model (`MovementSpecsView.scala`)

```scala
case class Model(
  fundFlow:                FundFlowPayload              = FundFlowPayload(),
  movementSpecs:           Seq[MovementSpecPayload]     = Seq(), // transient: cleared after zip
  movementSpecAndAccounts: Seq[MovementSpecAndAccounts] = Seq()
)
```

`movementSpecs` is only populated between step 2 and step 3. Once accounts arrive and the zip is computed, it is no longer used.

## Messages (`MovementSpecsView.Msg`)

```scala
enum Msg:
  case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
  case AccountsFromBackend(accounts: Seq[AccountPayload])
```

## Update Logic (`MovementSpecsView.update`)

- `MovementSpecsFromBackend(specs)` → store specs in model, emit `HttpHelper.fetchAccountsForMovementSpecs(fundFlow.id.get)`
- `AccountsFromBackend(accounts)` → call `zipAccountsAndMovementSpecs(accounts, model.movementSpecs)`, store result in `movementSpecAndAccounts`

## HttpHelper Changes

Add a decoder `movementSpecsAccountsDecoder` that decodes `Seq[AccountPayload]` and emits `Msg.MovementSpecsMsg(MovementSpecsView.Msg.AccountsFromBackend(...))`.

Add a function `fetchAccountsForMovementSpecs(fundFlowId: String)` using the existing `/fund-flows/:id/accounts` endpoint with the new decoder.

## WebApp Changes

No changes required. `NavigateToMovementSpecs` in `WebApp.update` already fires `fetchMovementSpecsByFundFlowID`. The second fetch (accounts) is triggered from within `MovementSpecsView.update` when `MovementSpecsFromBackend` arrives.

## View Changes

`MovementSpecsView.apply` iterates over `model.movementSpecAndAccounts` and displays `spec.inAccount.name` and `spec.outAccount.name` instead of raw account IDs.

## Error Handling

`zipAccountsAndMovementSpecs` throws `IllegalStateException` if an account ID referenced by a movement spec is not found in the accounts list. This is an internal data consistency error (backend data integrity issue), not a user error. No additional error handling is required beyond what the app already provides via `Msg.Error`.
