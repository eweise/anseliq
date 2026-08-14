# MovementSpecsView with MovementSpecAndAccounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change MovementSpecsView to fetch accounts sequentially after movement specs, zip them with `zipAccountsAndMovementSpecs`, and store the result as `Seq[MovementSpecAndAccounts]` in the model.

**Architecture:** Sequential fetch — navigation fires `fetchMovementSpecsByFundFlowID`; when specs arrive the view fires `fetchAccountsForMovementSpecs`; when accounts arrive they are zipped with the stored specs and stored as `movementSpecAndAccounts`. Two small, focused changes: one new decoder + fetch function in HttpHelper, and a full update to MovementSpecsView.

**Tech Stack:** Scala 3.8.1, Scala.js, Tyrian (Elm-style TEA), ZIO, upickle

---

## Files

- Modify: `frontend/src/main/scala/onparity/util/HttpHelper.scala`
- Modify: `frontend/src/main/scala/onparity/view/pages/MovementSpecsView.scala`

No other files change — `WebApp.scala`, `Page.scala`, and `model.scala` are unaffected.

---

### Task 1: Add decoder and fetch function to HttpHelper

**Files:**
- Modify: `frontend/src/main/scala/onparity/util/HttpHelper.scala`

- [ ] **Step 1: Add `fetchAccountsForMovementSpecs` to the HttpHelper object**

In `HttpHelper.scala`, add this function after `fetchMovementSpecsByFundFlowID` (line 78):

```scala
  def fetchAccountsForMovementSpecs(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), movementSpecsAccountsDecoder)
```

- [ ] **Step 2: Add `movementSpecsAccountsDecoder` to the ResponseDecoders object**

In `HttpHelper.scala`, add this decoder inside the `object ResponseDecoders` block, after `movementSpecsDecoder`:

```scala
    val movementSpecsAccountsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accounts = read[Seq[AccountPayload]](onResponse.body)
          Msg.MovementSpecsMsg(MovementSpecsView.Msg.AccountsFromBackend(accounts))
        ,
        error => Msg.Error(error.toString)
      )
```

- [ ] **Step 3: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: compilation succeeds (will fail on `MovementSpecsView.Msg.AccountsFromBackend` not existing yet — that's fine, proceed to Task 2).

---

### Task 2: Update MovementSpecsView

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/MovementSpecsView.scala`

- [ ] **Step 1: Replace the full file contents**

Replace the entire file with:

```scala
package onparity.view.pages

import onparity.common.model.{AccountPayload, FundFlowPayload, MovementSpecAndAccounts, MovementSpecPayload, zipAccountsAndMovementSpecs}
import onparity.model.Msg as AppMsg
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.Table
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MovementSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.MovementSpecsFromBackend(specs) =>
        (
          model.copy(movementSpecs = specs),
          HttpHelper.fetchAccountsForMovementSpecs(model.fundFlow.id.get) |+|
            PrettyLogger.info("fetching accounts for movement specs")
        )
      case Msg.AccountsFromBackend(accounts) =>
        val combined = zipAccountsAndMovementSpecs(accounts, model.movementSpecs)
        (model.copy(movementSpecAndAccounts = combined), PrettyLogger.info("zipped accounts and movement specs"))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Table.create(
          Seq("ID", "Name", "In Account", "Out Account"),
          model.movementSpecAndAccounts
            .sortBy(_.spec.name)
            .map(msa =>
              Seq(
                text(msa.spec.id.getOrElse("")),
                text(msa.spec.name),
                text(msa.inAccount.name),
                text(msa.outAccount.name)
              )
            )
        )
      )
    )

  enum Msg:
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case AccountsFromBackend(accounts: Seq[AccountPayload])

  case class Model(
    fundFlow:                FundFlowPayload              = FundFlowPayload(),
    movementSpecs:           Seq[MovementSpecPayload]     = Seq(),
    movementSpecAndAccounts: Seq[MovementSpecAndAccounts] = Seq()
  )
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: compilation succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/MovementSpecsView.scala \
        frontend/src/main/scala/onparity/util/HttpHelper.scala
git commit -m "feat: MovementSpecsView uses MovementSpecAndAccounts with sequential account fetch"
```

---

### Task 3: Manual verification

- [ ] **Step 1: Start the dev server**

```bash
sbt "frontend/devServer"
```

- [ ] **Step 2: Navigate to the MovementSpecs page**

Open the app, go to Fund Flows, click the Movement Specs button for a fund flow that has movement specs configured.

Expected: the table shows account **names** (not IDs) in the In Account and Out Account columns.

- [ ] **Step 3: Check browser console**

Open browser DevTools → Console.

Expected: no errors; you should see log lines `fetching accounts for movement specs` and `zipped accounts and movement specs`.
