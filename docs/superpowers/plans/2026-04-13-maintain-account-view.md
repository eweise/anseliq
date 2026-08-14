# MaintainAccountView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `MaintainAccountView` page for creating accounts, wired into the existing page/navigation system, with `createAccount` in `HttpHelper`.

**Architecture:** Mirrors the `MaintainFundFlowView` pattern — a page-scoped `Msg`/`Model`, a new `Page` object, a global navigation msg, and an HttpHelper function + decoder. `MaintainAccountView` receives a `FundFlowResponse` on navigation to pre-populate `fundFlowId`. Cancel returns to `AccountsView` via `NavigateToAccounts`.

**Tech Stack:** Scala 3, Tyrian (Elm-like Scala.js frontend), ZIO, upickle for JSON serialization.

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `frontend/src/main/scala/onparity/view/pages/MaintainAccountView.scala` | Create | Page Msg, Model, update, view |
| `frontend/src/main/scala/onparity/util/HttpHelper.scala` | Modify | `createAccount` function + `accountCreatedDecoder` |
| `frontend/src/main/scala/onparity/model/model.scala` | Modify | Add `MaintainAccountMsg`, `NavigateToMaintainAccount` to `Msg`; add `maintainAccountModel` to `Model` |
| `frontend/src/main/scala/onparity/page/Page.scala` | Modify | Add `MaintainAccount` page object |
| `frontend/src/main/scala/onparity/main/WebApp.scala` | Modify | Handle `NavigateToMaintainAccount` in global update |
| `frontend/src/main/scala/onparity/view/pages/AccountsView.scala` | Modify | Add "Add Account" button |

---

### Task 1: Create MaintainAccountView

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/MaintainAccountView.scala`

- [ ] **Step 1: Create the file**

```scala
package onparity.view.pages

import onparity.common.model.{AccountPayload, FundFlowResponse}
import onparity.model.Msg as AppMsg
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.{Buttons, Form}
import tyrian.Cmd
import tyrian.Html
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

import java.util.UUID

object MaintainAccountView:

  enum Msg:
    case AccountFormUpdate(account: AccountPayload)
    case CreateAccount(account: AccountPayload)
    case AccountCreated(account: AccountPayload)

  case class Model(
    fundFlow: FundFlowResponse = FundFlowResponse(),
    account:  AccountPayload   = AccountPayload(
      id         = "",
      name       = "",
      fundFlowId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
      balance    = BigDecimal(0)
    )
  )

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.AccountFormUpdate(account) =>
        (model.copy(account = account), PrettyLogger.info(s"account form modified: $account"))
      case Msg.CreateAccount(account) =>
        if account.name.isBlank then (model, Cmd.emit(AppMsg.Error("Name is required")))
        else (model, HttpHelper.createAccount(account) |+| PrettyLogger.info("creating account"))
      case Msg.AccountCreated(account) =>
        (model.copy(account = account), PrettyLogger.info("created account"))

  def apply(model: Model): Html[AppMsg] =
    val account = model.account
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Account")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(account.name),
          onChangFn    = Some(value => AppMsg.MaintainAccountMsg(Msg.AccountFormUpdate(account.copy(name = value))))
        ),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateToAccounts(model.fundFlow)),
          Buttons.linkButton("Save", AppMsg.MaintainAccountMsg(Msg.CreateAccount(account)))
        )
      )
    )
```

Note: `AppMsg.MaintainAccountMsg` and `AppMsg.NavigateToMaintainAccount` are referenced here — they will be added to the `Msg` enum in Task 3. The file will not compile until Task 3 is complete.

- [ ] **Step 2: Verify — do not compile yet** (dependencies not wired)

---

### Task 2: Add createAccount and accountCreatedDecoder to HttpHelper

**Files:**
- Modify: `frontend/src/main/scala/onparity/util/HttpHelper.scala`

- [ ] **Step 1: Add `createAccount` function**

In `HttpHelper.scala`, after the existing `createFundFlow` function (line 41), add:

```scala
  def createAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.post(HttpPath.url(HttpPath.maintainAccount), Body.json(write(account))), accountCreatedDecoder)
```

- [ ] **Step 2: Add `accountCreatedDecoder` to ResponseDecoders**

In the `ResponseDecoders` object, after `accountDecoder`, add:

```scala
    val accountCreatedDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountPayload = read[AccountPayload](onResponse.body)
          Msg.MaintainAccountMsg(MaintainAccountView.Msg.AccountCreated(accountPayload)),
        error => Msg.Error(error.toString)
      )
```

- [ ] **Step 3: Add the `accountCreatedDecoder` import in `HttpHelper`**

At the top of `HttpHelper.scala`, `accountCreatedDecoder` is referenced inside `sendWithAuthToken` — it is defined in `ResponseDecoders` and already imported via `import HttpHelper.ResponseDecoders.*` (line 5). No additional import needed.

- [ ] **Step 4: Add `MaintainAccountView` to the import in HttpHelper**

Change line 8 from:
```scala
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, Welcome}
```
to:
```scala
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, Welcome}
```

Note: This file will not compile until Task 3 adds `MaintainAccountMsg` to the global `Msg` enum.

- [ ] **Step 5: Verify — do not compile yet** (dependencies not wired)

---

### Task 3: Wire model.scala — add Msg cases and Model field

**Files:**
- Modify: `frontend/src/main/scala/onparity/model/model.scala`

- [ ] **Step 1: Add `MaintainAccountView` to imports**

Change line 6 from:
```scala
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, ToggleView, Welcome}
```
to:
```scala
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, ToggleView, Welcome}
```

- [ ] **Step 2: Add two cases to the `Msg` enum**

After line 25 (`case NavigateToAccounts(ff: FundFlowResponse)`), add:
```scala
  case NavigateToMaintainAccount(ff: FundFlowResponse)
```

After line 33 (`case AccountsMsg(msg: AccountsView.Msg)`), add:
```scala
  case MaintainAccountMsg(msg: MaintainAccountView.Msg)
```

- [ ] **Step 3: Add `maintainAccountModel` field to `Model` case class**

After line 46 (`accountsModel: AccountsView.Model,`), add:
```scala
  maintainAccountModel:  MaintainAccountView.Model,
```

- [ ] **Step 4: Initialize `maintainAccountModel` in `Model.init`**

After line 71 (`accountsModel = AccountsView.Model()`), add:
```scala
    maintainAccountModel  = MaintainAccountView.Model(),
```

- [ ] **Step 5: Verify compilation**

From the project root:
```bash
sbt "frontend/compile"
```
Expected: compilation succeeds. If it fails, check that all four edits above are in place.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/MaintainAccountView.scala \
        frontend/src/main/scala/onparity/util/HttpHelper.scala \
        frontend/src/main/scala/onparity/model/model.scala
git commit -m "feat: add MaintainAccountView and wire Msg/Model"
```

---

### Task 4: Add Page.MaintainAccount

**Files:**
- Modify: `frontend/src/main/scala/onparity/page/Page.scala`

- [ ] **Step 1: Add `MaintainAccount` page object**

After the closing brace of `Page.Accounts` (line 79), add:

```scala
  object MaintainAccount extends Page {
    val path: String = "/components/maintain-account"
    val render: Model => Html[Msg] = m => MaintainAccountView(m.maintainAccountModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Account page")
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainAccountMsg(sub) =>
        val (sub2, cmd) = MaintainAccountView.update(model.maintainAccountModel, sub)
        (model.copy(maintainAccountModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }
```

- [ ] **Step 2: Verify compilation**

```bash
sbt "frontend/compile"
```
Expected: compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/page/Page.scala
git commit -m "feat: add Page.MaintainAccount"
```

---

### Task 5: Handle NavigateToMaintainAccount in WebApp

**Files:**
- Modify: `frontend/src/main/scala/onparity/main/WebApp.scala`

- [ ] **Step 1: Add `MaintainAccountView` to WebApp imports**

Change line 22 from:
```scala
import onparity.view.pages.{AccountsView, MaintainFundFlowView}
```
to:
```scala
import onparity.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView}
```

- [ ] **Step 2: Add handler for `NavigateToMaintainAccount`**

After the `NavigateToAccounts` handler (lines 88–94), add:

```scala
    case Msg.NavigateToMaintainAccount(ff) =>
      (
        model.copy(maintainAccountModel = MaintainAccountView.Model(
          fundFlow = ff,
          account  = AccountPayload(
            id         = "",
            name       = "",
            fundFlowId = java.util.UUID.fromString(ff.id),
            balance    = BigDecimal(0)
          )
        )),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainAccount))
      )
```

- [ ] **Step 3: Add `AccountPayload` to WebApp imports**

Change line 14 from:
```scala
import onparity.model.*
```
This already imports `*` from `onparity.model`. But `AccountPayload` is in `onparity.common.model`. Add an import after line 13:
```scala
import onparity.common.model.AccountPayload
```

- [ ] **Step 4: Verify compilation**

```bash
sbt "frontend/compile"
```
Expected: compilation succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/main/WebApp.scala
git commit -m "feat: handle NavigateToMaintainAccount in WebApp"
```

---

### Task 6: Add "Add Account" button to AccountsView

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/AccountsView.scala`

- [ ] **Step 1: Add the button to the view**

In `AccountsView.apply`, change the `div(cls := "my-8 text-center")` block. Currently (lines 31–52):

```scala
  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        span(onClick(AppMsg.NavigateTo(Page.FundFlows)))(text(s"${model.fundFlow.name}: Accounts View")),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(AppMsg.AccountsMsg(Msg.GetReconAccounts))
        )(Icons.user),
        Table.create(
          ...
        )
      )
    )
```

Add the import for `Buttons` at the top of the file:
```scala
import onparity.view.components.{Icons, Table, Buttons}
```

Then add a "Add Account" button inside the `div(cls := "my-8 text-center")` block, after the `button(...)` for `GetReconAccounts`:

```scala
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow)),
```

The full updated `apply` method:

```scala
  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        span(onClick(AppMsg.NavigateTo(Page.FundFlows)))(text(s"${model.fundFlow.name}: Accounts View")),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(AppMsg.AccountsMsg(Msg.GetReconAccounts))
        )(Icons.user),
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow)),
        Table.create(
          Seq(
            "ID",
            "Name",
            "Balance"
          ),
          model.accounts.map(account =>
            Seq(
              text(account.id),
              text(account.name),
              text(account.balance.toString)
            )
          )
        )
      )
    )
```

- [ ] **Step 2: Verify compilation**

```bash
sbt "frontend/compile"
```
Expected: compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/AccountsView.scala
git commit -m "feat: add Add Account button to AccountsView"
```
