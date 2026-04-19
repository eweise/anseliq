# ReconSpecView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ReconSpecsView` page that lists reconciliation specs for a selected account, navigated from `AccountsView`, with movement spec names resolved by fetching the fund flow's movement specs.

**Architecture:** Follows the existing Tyrian (Elm-like) pattern — new `ReconSpecsView` object with `Msg`/`Model`/`apply`, wired through `Page`, `model.scala`, and `WebApp`. Two parallel HTTP fetches fire on navigation: recon specs by accountID, movement specs by fundFlowID. The view builds a `Map[String, String]` (id → name) to resolve movement spec names inline.

**Tech Stack:** Scala 3, Scala.js, Tyrian, upickle, Tailwind CSS

---

## File Map

| Action | File |
|--------|------|
| **Create** | `frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/util/HttpHelper.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/model/model.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/page/Page.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/main/WebApp.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala` |

---

### Task 1: Create ReconSpecsView.scala

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.components.Table
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object ReconSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.ReconSpecsFromBackend(specs) =>
        (model.copy(reconSpecs = specs), Cmd.None)
      case Msg.MovementSpecsFromBackend(specs) =>
        (model.copy(movementSpecs = specs), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    val specNameById = model.movementSpecs.flatMap(ms => ms.id.map(_ -> ms.name)).toMap
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Table.create(
          Seq("Description", "Aging Limit", "Relationship", "In Movement Spec", "Out Movement Spec"),
          model.reconSpecs
            .sortBy(_.description)
            .map(spec =>
              Seq(
                text(spec.description),
                text(spec.agingLimit.toString),
                text(spec.relationship),
                text(specNameById.getOrElse(spec.inMovementSpecId, spec.inMovementSpecId)),
                text(specNameById.getOrElse(spec.outMovementSpecId, spec.outMovementSpecId))
              )
            )
        )
      )
    )

  enum Msg:
    case ReconSpecsFromBackend(specs: Seq[ReconciliationSpecPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])

  case class Model(
    account:       AccountPayload                 = AccountPayload(),
    reconSpecs:    Seq[ReconciliationSpecPayload] = Seq(),
    movementSpecs: Seq[MovementSpecPayload]       = Seq()
  )
```

- [ ] **Step 2: Verify compile**

Run: `sbt "frontend/Compile/compile"`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala
git commit -m "feat: add ReconSpecsView with Msg/Model/apply"
```

---

### Task 2: Add HttpHelper fetch methods and decoders

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`

- [ ] **Step 1: Add import for ReconSpecsView at the top of the import block**

In `HttpHelper.scala`, change:
```scala
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView, Welcome}
```
to:
```scala
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView, ReconSpecsView, Welcome}
```

Also add `ReconciliationSpecPayload` to the common model import:
```scala
import perfectpair.common.model.*
```
(already uses `*`, so no change needed here)

- [ ] **Step 2: Add the two fetch methods to the HttpHelper object body, after `fetchMovementSpecsByFundFlowID`**

```scala
  def fetchReconSpecsByAccountID(accountID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.account}/$accountID/recon-specs"), reconSpecsDecoder)

  def fetchMovementSpecsForReconView(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/movement-specs"), reconSpecsMovementSpecsDecoder)
```

- [ ] **Step 3: Add the two decoders inside the `ResponseDecoders` object, after `movementSpecDecoder`**

```scala
    val reconSpecsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[ReconciliationSpecPayload]](onResponse.body)
          Msg.ReconSpecsMsg(ReconSpecsView.Msg.ReconSpecsFromBackend(specs))
        ,
        error => Msg.Error(error.toString)
      )

    val reconSpecsMovementSpecsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[MovementSpecPayload]](onResponse.body)
          Msg.ReconSpecsMsg(ReconSpecsView.Msg.MovementSpecsFromBackend(specs))
        ,
        error => Msg.Error(error.toString)
      )
```

Note: `Msg.ReconSpecsMsg` does not exist yet — the file will not compile until Task 3 adds it to `model.scala`. That's expected.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/util/HttpHelper.scala
git commit -m "feat: add HttpHelper fetch methods and decoders for ReconSpecsView"
```

---

### Task 3: Update model.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`

- [ ] **Step 1: Add `ReconSpecsMsg` and `NavigateToReconSpecs` to the `Msg` enum**

In the `Msg` enum in `model.scala`, add after `case MaintainMovementSpecsMsg(msg: MaintainMovementSpecsView.Msg)`:
```scala
  case ReconSpecsMsg(msg: ReconSpecsView.Msg)
```

And add after `case NavigateToMaintainMovementSpec(...)`:
```scala
  case NavigateToReconSpecs(account: AccountPayload)
```

- [ ] **Step 2: Add `reconSpecsModel` field to the `Model` case class**

Add `reconSpecsModel` as a field with a default value at the end of the `Model` case class parameter list (after `maintainMovementSpecModel`):
```scala
  reconSpecsModel: ReconSpecsView.Model = ReconSpecsView.Model(),
```

- [ ] **Step 3: Add `reconSpecsModel` to `Model.init`**

In `Model.init`, add after `maintainMovementSpecModel = MaintainMovementSpecsView.Model()`:
```scala
    reconSpecsModel = ReconSpecsView.Model(),
```

- [ ] **Step 4: Verify compile**

Run: `sbt "frontend/Compile/compile"`
Expected: no errors (HttpHelper now also compiles since `Msg.ReconSpecsMsg` exists)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/perfectpair/model/model.scala
git commit -m "feat: add ReconSpecsMsg, NavigateToReconSpecs, and reconSpecsModel to global model"
```

---

### Task 4: Add Page.ReconSpecs to Page.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`

- [ ] **Step 1: Add `Page.ReconSpecs` object inside the `Page` object, after `Page.MaintainMovementSpec`**

```scala
  object ReconSpecs extends Page {
    val path:   String             = "/components/recon-specs"
    val render: Model => Html[Msg] = m => ReconSpecsView(m.reconSpecsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.ReconSpecsMsg(sub) =>
        val (sub2, cmd) = ReconSpecsView.update(model.reconSpecsModel, sub)
        (model.copy(reconSpecsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Recon Specs page")
  }
```

- [ ] **Step 2: Verify compile**

Run: `sbt "frontend/Compile/compile"`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/page/Page.scala
git commit -m "feat: add Page.ReconSpecs"
```

---

### Task 5: Handle NavigateToReconSpecs in WebApp.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Add `ReconSpecsView` to the explicit import in WebApp.scala**

Change:
```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView}
```
to:
```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView, ReconSpecsView}
```

- [ ] **Step 2: Add the `NavigateToReconSpecs` case to the `update` function, after the `NavigateToMovementSpecs` case**

```scala
    case Msg.NavigateToReconSpecs(account) =>
      (
        model.copy(reconSpecsModel = ReconSpecsView.Model(account = account)),
        HttpHelper.fetchReconSpecsByAccountID(account.id.get) |+|
          HttpHelper.fetchMovementSpecsForReconView(account.fundFlowId) |+|
          Cmd.Emit(Msg.NavigateTo(Page.ReconSpecs)) |+|
          PrettyLogger.info("NavigateToReconSpecs")
      )
```

- [ ] **Step 3: Verify compile**

Run: `sbt "frontend/Compile/compile"`
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/main/WebApp.scala
git commit -m "feat: handle NavigateToReconSpecs in WebApp"
```

---

### Task 6: Add "Recon Specs" button to AccountsView.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala`

- [ ] **Step 1: Update the `Table.create` call to add a "Recon Specs" button column**

Replace the existing `Table.create` call in `AccountsView.apply`:
```scala
        Table.create(
          Seq(
            "ID",
            "Name",
            "Balance"
          ),
          model.accounts
            .sortBy(_.name)
            .map(account =>
              Seq(
                text(account.id.getOrElse("")),
                text(account.name),
                text(account.balance.toString),
                Buttons.textButton("Edit", AppMsg.AccountsMsg(Msg.SelectAccount(account)))
              )
            )
        )
```
with:
```scala
        Table.create(
          Seq(
            "ID",
            "Name",
            "Balance",
            "",
            ""
          ),
          model.accounts
            .sortBy(_.name)
            .map(account =>
              Seq(
                text(account.id.getOrElse("")),
                text(account.name),
                text(account.balance.toString),
                Buttons.textButton("Edit", AppMsg.AccountsMsg(Msg.SelectAccount(account))),
                Buttons.textButton("Recon Specs", AppMsg.NavigateToReconSpecs(account))
              )
            )
        )
```

- [ ] **Step 2: Verify compile**

Run: `sbt "frontend/Compile/compile"`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/AccountsView.scala
git commit -m "feat: add Recon Specs navigation button to AccountsView"
```

---

### Task 7: End-to-end verification

- [ ] **Step 1: Start the dev server**

In the project root:
```bash
sbt "frontend/devServer"
```
Open the browser at `http://localhost:9876`

- [ ] **Step 2: Navigate to an account's recon specs**

1. Log in (username: `Scala`, password: `nopassword`)
2. Navigate to **Fund Flows** and click a fund flow
3. Click the **Accounts** button for a fund flow
4. On the accounts table, click **Recon Specs** for one of the accounts
5. Verify the page loads and displays the recon specs table with columns: Description, Aging Limit, Relationship, In Movement Spec, Out Movement Spec

- [ ] **Step 3: Verify movement spec name resolution**

Confirm that the "In Movement Spec" and "Out Movement Spec" columns show movement spec names (not raw UUIDs).

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -p
git commit -m "fix: address any issues found during end-to-end verification"
```
