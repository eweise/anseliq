# Unreconciled Movements Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a frontend page that lists unreconciled movements for a given MovementSpec, reached from MovementSpecsView, with account names resolved from IDs.

**Architecture:** Seven coordinated changes following the existing Tyrian (TEA) pattern — one new view file and six modifications to wire it into the routing, navigation, HTTP, and global model layers. All changes compile together; each task can be committed independently after the final compile step.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-architecture), ZIO, upickle, Tailwind CSS

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsView.scala` | New page view, model, msg |
| Modify | `frontend/src/main/scala/perfectpair/model/model.scala` | Add model field, two new Msg cases |
| Modify | `frontend/src/main/scala/perfectpair/util/HttpHelper.scala` | Add fetch method + decoder |
| Modify | `frontend/src/main/scala/perfectpair/page/Page.scala` | Register new Page object |
| Modify | `frontend/src/main/scala/perfectpair/route/route.scala` | Add URL route |
| Modify | `frontend/src/main/scala/perfectpair/main/WebApp.scala` | Handle NavigateToUnreconciledMovements |
| Modify | `frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala` | Add "View Unreconciled" button per row |

---

### Task 1: Create UnreconciledMovementsView

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsView.scala`

- [ ] **Step 1: Create the view file**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object UnreconciledMovementsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.MovementsFromBackend(movements) =>
        (model.copy(movements = movements), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    val accountNameById = model.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(s"${model.movementSpec.name} — Unreconciled Movements")
        ),
        Buttons.linkButton("Back to Movement Specs", AppMsg.NavigateToMovementSpecs(model.fundFlow)),
        Table.create(
          Seq("Reference ID", "Amount", "In Account", "Out Account"),
          model.movements.map(m =>
            Seq(
              text(m.referenceId),
              text(m.amount.toString),
              text(accountNameById.getOrElse(m.inAccountId, m.inAccountId)),
              text(accountNameById.getOrElse(m.outAccountId, m.outAccountId))
            )
          )
        )
      )
    )

  enum Msg:
    case MovementsFromBackend(movements: Seq[MovementPayload])

  case class Model(
    fundFlow:     FundFlowPayload      = FundFlowPayload(),
    movementSpec: MovementSpecPayload  = MovementSpecPayload(),
    accounts:     Seq[AccountPayload]  = Seq(),
    movements:    Seq[MovementPayload] = Seq()
  )
```

---

### Task 2: Update global model

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`

- [ ] **Step 1: Add `NavigateToUnreconciledMovements` and `UnreconciledMovementsMsg` to the `Msg` enum**

In the `// Navigation with context` block, add after `NavigateToIngest`:
```scala
  case NavigateToUnreconciledMovements(movementSpec: MovementSpecPayload, accounts: Seq[AccountPayload], fundFlow: FundFlowPayload)
```

In the `// Page message wrappers` block, add after `JobsMsg`:
```scala
  case UnreconciledMovementsMsg(msg: UnreconciledMovementsView.Msg)
```

- [ ] **Step 2: Add `unreconciledMovementsModel` to the `Model` case class**

In the case class body, add after the `jobsModel` field:
```scala
  unreconciledMovementsModel: UnreconciledMovementsView.Model = UnreconciledMovementsView.Model(),
```

- [ ] **Step 3: Add `unreconciledMovementsModel` to `Model.init`**

In `object Model`, add to the `Model(...)` constructor call after `jobsModel = JobsView.Model()`:
```scala
    unreconciledMovementsModel = UnreconciledMovementsView.Model()
```

> Note: `model.scala` already uses `import perfectpair.view.pages.*`, so `UnreconciledMovementsView` is auto-imported once the file from Task 1 exists.

---

### Task 3: Add HTTP fetch method and decoder

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`

- [ ] **Step 1: Add `fetchUnreconciledMovements` method**

In `object HttpHelper`, add after `fetchJobsByType`:
```scala
  def fetchUnreconciledMovements(movementSpecId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.movements}/$movementSpecId/unreconciled"),
      ResponseDecoders.unreconciledMovementsDecoder
    )
```

- [ ] **Step 2: Add `unreconciledMovementsDecoder` to `ResponseDecoders`**

In `object ResponseDecoders`, add after `jobsDecoder`:
```scala
    val unreconciledMovementsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val movements = read[Seq[MovementPayload]](onResponse.body)
          Msg.UnreconciledMovementsMsg(UnreconciledMovementsView.Msg.MovementsFromBackend(movements))
        ,
        error => Msg.Error(error.toString)
      )
```

> Note: `MovementPayload` and `UnreconciledMovementsView` are already in scope via the existing `import perfectpair.common.model.*` and `import perfectpair.view.pages.*`.

---

### Task 4: Register the Page

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`

- [ ] **Step 1: Add `Page.UnreconciledMovements` object**

Add after `object Jobs extends Page { ... }` and before `object Toggle extends Page { ... }`:
```scala
  object UnreconciledMovements extends Page {
    val path:   String             = "/components/unreconciled-movements"
    val render: Model => Html[Msg] = m => UnreconciledMovementsView(m.unreconciledMovementsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.UnreconciledMovementsMsg(sub) =>
        val (sub2, cmd) = UnreconciledMovementsView.update(model.unreconciledMovementsModel, sub)
        (model.copy(unreconciledMovementsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Unreconciled Movements page") |+|
        HttpHelper.fetchUnreconciledMovements(model.unreconciledMovementsModel.movementSpec.id.get)
  }
```

---

### Task 5: Add the URL route

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/route/route.scala`

- [ ] **Step 1: Add route entry**

In `object ComponentDemo`, add after `case "jobs" => Page.Jobs`:
```scala
        case "unreconciled-movements" => Page.UnreconciledMovements
```

---

### Task 6: Handle navigation in WebApp

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Add `UnreconciledMovementsView` to the import list**

Change:
```scala
import perfectpair.view.pages.{AccountsView, IngestView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MaintainReconSpecView, MovementSpecsView, ReconSpecsView}
```
To:
```scala
import perfectpair.view.pages.{AccountsView, IngestView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MaintainReconSpecView, MovementSpecsView, ReconSpecsView, UnreconciledMovementsView}
```

- [ ] **Step 2: Handle `NavigateToUnreconciledMovements`**

In the `update` function, add after the `Msg.NavigateToIngest` case:
```scala
    case Msg.NavigateToUnreconciledMovements(movementSpec, accounts, fundFlow) =>
      (
        model.copy(unreconciledMovementsModel = UnreconciledMovementsView.Model(
          fundFlow     = fundFlow,
          movementSpec = movementSpec,
          accounts     = accounts
        )),
        Cmd.Emit(Msg.NavigateTo(Page.UnreconciledMovements))
      )
```

---

### Task 7: Add "View Unreconciled" button to MovementSpecsView

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala`

- [ ] **Step 1: Add `ViewUnreconciled` to the `Msg` enum**

Add after `case SelectMovementSpec`:
```scala
    case ViewUnreconciled(msa: MovementSpecAndAccounts)
```

- [ ] **Step 2: Handle `ViewUnreconciled` in `update`**

Add after the `Msg.SelectMovementSpec` case:
```scala
      case Msg.ViewUnreconciled(msa) =>
        (model, Cmd.emit(AppMsg.NavigateToUnreconciledMovements(msa.spec, model.accounts, model.fundFlow)))
```

- [ ] **Step 3: Add the button to each table row**

Replace the last cell in each row from:
```scala
                Buttons.textButton("Edit", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(Some(msa))))
```
To:
```scala
                div(cls := "flex gap-2")(
                  Buttons.textButton("View Unreconciled", AppMsg.MovementSpecsMsg(Msg.ViewUnreconciled(msa))),
                  Buttons.textButton("Edit", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(Some(msa))))
                )
```

---

### Task 8: Compile and verify

- [ ] **Step 1: Compile the frontend**

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/fastLinkJS"
```

Expected: compilation succeeds with no errors.

- [ ] **Step 2: Start the dev server and manually verify**

```bash
cd /Users/eweise/projects/perfectpair && sbt devServer
```

Navigate to the Movement Specs page, confirm each row has a "View Unreconciled" button, click it, confirm the Unreconciled Movements page loads with the correct spec name and a table showing account names (not IDs). Confirm the "Back to Movement Specs" button returns to the previous page.

- [ ] **Step 3: Commit**

```bash
git add \
  frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsView.scala \
  frontend/src/main/scala/perfectpair/model/model.scala \
  frontend/src/main/scala/perfectpair/util/HttpHelper.scala \
  frontend/src/main/scala/perfectpair/page/Page.scala \
  frontend/src/main/scala/perfectpair/route/route.scala \
  frontend/src/main/scala/perfectpair/main/WebApp.scala \
  frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala
git commit -m "feat: add Unreconciled Movements page navigable from MovementSpecsView"
```
