# MaintainAccountView Design

**Date:** 2026-04-13

## Overview

Add a `MaintainAccountView` page and supporting HttpHelper operations to create an account, following the same pattern established by `MaintainFundFlowView` and `createFundFlow`. Update is out of scope — the backend PUT endpoint will be added later.

## Files Changed

### New: `frontend/src/main/scala/onparity/view/pages/MaintainAccountView.scala`

Three Msg types:
- `AccountFormUpdate(account: AccountPayload)` — updates form state in model
- `CreateAccount(account: AccountPayload)` — triggers HTTP POST to backend
- `AccountCreated(account: AccountPayload)` — handles server response

Model:
```scala
case class Model(
  fundFlow: FundFlowResponse = FundFlowResponse(),
  account:  AccountPayload   = AccountPayload(id = "", name = "", fundFlowId = UUID.fromString("00000000-0000-0000-0000-000000000000"), balance = BigDecimal(0))
)
```

`update` handles the three Msg cases:
- `AccountFormUpdate` → copy model with updated account
- `CreateAccount` → validate name is not blank, then call `HttpHelper.createAccount`
- `AccountCreated` → copy model with returned account

View renders a form with a single `name` field (id, balance, properties are system-managed). Cancel emits `NavigateToAccounts(model.fundFlow)` to return with correct fund flow context. Save emits `CreateAccount`.

### Modified: `frontend/src/main/scala/onparity/util/HttpHelper.scala`

Add:
- `def createAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken]` — POST to `HttpPath.maintainAccount` (`/api/v1/account`) with `write(account)` body
- `val accountCreatedDecoder: Decoder[Msg]` — reads `AccountPayload` from response body, emits `Msg.MaintainAccountMsg(MaintainAccountView.Msg.AccountCreated(...))`

### Modified: `frontend/src/main/scala/onparity/model/model.scala`

In `Msg` enum add:
- `case NavigateToMaintainAccount(ff: FundFlowResponse)`
- `case MaintainAccountMsg(msg: MaintainAccountView.Msg)`

In `Model` case class add:
- `maintainAccountModel: MaintainAccountView.Model`

In `Model.init` initialize:
- `maintainAccountModel = MaintainAccountView.Model()`

### Modified: `frontend/src/main/scala/onparity/page/Page.scala`

Add `MaintainAccount` page object:
- `path = "/components/maintain-account"`
- `render = m => MaintainAccountView(m.maintainAccountModel)`
- `update` delegates `MaintainAccountMsg` to `MaintainAccountView.update`, copying `maintainAccountModel`

### Modified: `frontend/src/main/scala/onparity/main/WebApp.scala`

Add handler for `NavigateToMaintainAccount(ff)`:
- Set `maintainAccountModel = MaintainAccountView.Model(fundFlow = ff, account = AccountPayload(id = "", name = "", fundFlowId = UUID.fromString(ff.id), balance = BigDecimal(0)))`
- Emit `NavigateTo(Page.MaintainAccount)`

### Modified: `frontend/src/main/scala/onparity/view/pages/AccountsView.scala`

Add an "Add Account" button that emits `AppMsg.NavigateToMaintainAccount(model.fundFlow)`.

## Data Flow

1. User clicks "Add Account" in `AccountsView` → emits `NavigateToMaintainAccount(model.fundFlow)`
2. `WebApp.update` handles it: initializes `MaintainAccountView.Model` with `fundFlow` and blank account, navigates to `Page.MaintainAccount`
3. User types a name → `AccountFormUpdate` updates form state
4. User clicks Save → `CreateAccount` validates name, calls `HttpHelper.createAccount`
5. `HttpHelper.createAccount` POSTs to `/api/v1/account`, response decoded by `accountCreatedDecoder`
6. `AccountCreated` received → model updated with returned account
7. User clicks Cancel → `NavigateToAccounts(model.fundFlow)` restores Accounts page with correct context

## Out of Scope

- Backend PUT endpoint for account updates (to be added later)
- `updateAccount` HttpHelper function (to be added when backend endpoint exists)
- Account properties and balance fields in the form (system-managed)
