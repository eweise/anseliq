# Accounts Reconcile Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Reconcile" button to each row in AccountsView that calls `POST /api/v1/account/:id/reconcile` and shows an inline status badge (Running / Done / Error) that persists until navigation.

**Architecture:** Mirrors the per-row status tracking pattern in `IngestView` — a `Map[String, ReconcileStatus]` in the model keyed by account ID, updated by new `Msg` cases. The HTTP call goes through `HttpHelper` using the existing `sendWithAuthToken` mechanism.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-style FRP), Tailwind CSS, upickle, ZIO

---

## Files

- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`
  - Add `reconcileAccount(accountId: String)` function
  - Add `reconcileDecoder(accountId: String)` in `ResponseDecoders`
- Modify: `frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala`
  - Add `ReconcileStatus` enum
  - Add `reconcileStatuses` field to `Model`
  - Add `ReconcileAccount` and `ReconcileResult` to `Msg`
  - Update `update` to handle new messages
  - Update `apply` to render button and badge

---

### Task 1: Add HTTP helper for reconcile

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`

There are no automated frontend tests; verification is by compilation and manual browser testing.

- [ ] **Step 1: Add `reconcileDecoder` to `ResponseDecoders` in `HttpHelper.scala`**

Add after `ingestDecoder` (around line 301):

```scala
def reconcileDecoder(accountId: String): Decoder[Msg] =
  Decoder(
    _ => Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, isSuccess = true)),
    error => Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, isSuccess = false))
  )
```

- [ ] **Step 2: Add `reconcileAccount` function to `HttpHelper`**

Add after `ingestFile` (around line 150):

```scala
def reconcileAccount(accountId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
  sendWithAuthToken(
    Request.post(HttpPath.url(s"${HttpPath.account}/$accountId/reconcile"), Body.Empty),
    ResponseDecoders.reconcileDecoder(accountId)
  )
```

- [ ] **Step 3: Verify compilation**

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/fastLinkJS"
```

Expected: BUILD SUCCESS with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/util/HttpHelper.scala
git commit -m "feat: add reconcileAccount HTTP helper"
```

---

### Task 2: Update AccountsView with button and status badge

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala`

- [ ] **Step 1: Replace the contents of `AccountsView.scala` with the updated version**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object AccountsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.SelectAccount(account) =>
        (model, Cmd.Emit(AppMsg.NavigateToMaintainAccount(model.fundFlow, Some(account))))
      case Msg.AccountsFromBackend(accounts) =>
        println("AccountsView Msg.AccountsFromBackend(accounts) " + accounts)
        (model.copy(accounts = accounts), PrettyLogger.info("updated model with reconAccounts"))
      case Msg.ReconcileAccount(accountId) =>
        (
          model.copy(reconcileStatuses = model.reconcileStatuses.updated(accountId, ReconcileStatus.InProgress)),
          HttpHelper.reconcileAccount(accountId)
        )
      case Msg.ReconcileResult(accountId, isSuccess) =>
        val status = if isSuccess then ReconcileStatus.Success else ReconcileStatus.Failed("Error")
        (model.copy(reconcileStatuses = model.reconcileStatuses.updated(accountId, status)), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    println("AccountView.apply fundFlow = " + model.fundFlow)
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow, None)),
        Table.create(
          Seq("Name", "Balance", "", "", "", ""),
          model.accounts
            .sortBy(_.name)
            .map(account =>
              val accountId = account.id.getOrElse("")
              val status    = model.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
              Seq(
                text(account.name),
                text(account.balance.toString),
                Buttons.textButton("Edit", AppMsg.AccountsMsg(Msg.SelectAccount(account))),
                Buttons.textButton("Recon Specs", AppMsg.NavigateToReconSpecs(account)),
                Buttons.textButton("Reconcile", AppMsg.AccountsMsg(Msg.ReconcileAccount(accountId))),
                statusBadge(status)
              )
            )
        )
      )
    )

  private def statusBadge(status: ReconcileStatus): Html[AppMsg] =
    status match
      case ReconcileStatus.Idle        => span()(text(""))
      case ReconcileStatus.InProgress  => span(cls := "text-xs text-blue-500")(text("Running…"))
      case ReconcileStatus.Success     => span(cls := "text-xs text-green-600 font-medium")(text("Done"))
      case ReconcileStatus.Failed(msg) => span(cls := "text-xs text-red-500")(text(s"Error: $msg"))

  enum ReconcileStatus:
    case Idle
    case InProgress
    case Success
    case Failed(msg: String)

  enum Msg:
    case SelectAccount(account: AccountPayload)
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case ReconcileAccount(accountId: String)
    case ReconcileResult(accountId: String, isSuccess: Boolean)

  case class Model(
    fundFlow:          FundFlowPayload              = FundFlowPayload(),
    accounts:          Seq[AccountPayload]          = Seq(),
    reconcileStatuses: Map[String, ReconcileStatus] = Map()
  )
```

- [ ] **Step 2: Verify compilation**

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/fastLinkJS"
```

Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala
git commit -m "feat: add reconcile button with status badge to AccountsView"
```

---

### Task 3: Manual verification

- [ ] **Step 1: Start the backend**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/run"
```

- [ ] **Step 2: Start the frontend dev server**

In a separate terminal:

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/devServer"
```

- [ ] **Step 3: Test the happy path**

1. Open the app in a browser (typically `http://localhost:5173`)
2. Navigate to a Fund Flow → Accounts
3. Each account row should show a "Reconcile" button
4. Click "Reconcile" on an account — the badge in that row should immediately show "Running…" (blue)
5. After the response returns, it should change to "Done" (green)
6. The status stays visible; other rows are unaffected

- [ ] **Step 4: Test the error path**

Stop the backend and click "Reconcile" again — the badge should show "Error: …" (red).
