# MaintainReconSpecView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `MaintainReconSpecView` form page for create, update, and delete of `ReconciliationSpecPayload`, navigable from `ReconSpecsView`, with dropdowns for `inMovementSpecId` and `outMovementSpecId` populated from the account's `MovementSpecPayload`s.

**Architecture:** Follows the existing Tyrian MVU pattern — a new view file owns its own `Msg`/`Model`, wired into the global `Msg` enum, `Model`, `Page`, and `WebApp` router. HTTP calls go through `HttpHelper` using the existing `sendWithAuthToken` pattern. Navigation back to `ReconSpecsView` reuses the existing `NavigateToReconSpecs` message.

**Tech Stack:** Scala 3, Scala.js, Tyrian (ZIO), upickle for JSON, Tailwind CSS (Material Design tokens)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `frontend/src/main/scala/perfectpair/util/PayloadAction.scala` | Modify | Add `Delete` and `Deleted` cases |
| `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala` | **Create** | Form UI + update logic for recon spec CRUD |
| `frontend/src/main/scala/perfectpair/util/HttpHelper.scala` | Modify | Add `createReconSpec`, `updateReconSpec`, `deleteReconSpec` + decoders |
| `frontend/src/main/scala/perfectpair/model/model.scala` | Modify | Add `NavigateToMaintainReconSpec` msg, `MaintainReconSpecMsg` wrapper, `maintainReconSpecModel` field |
| `frontend/src/main/scala/perfectpair/page/Page.scala` | Modify | Add `MaintainReconSpec` page object |
| `frontend/src/main/scala/perfectpair/main/WebApp.scala` | Modify | Handle `NavigateToMaintainReconSpec` and `MaintainReconSpecMsg` |
| `frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala` | Modify | Add Add/Edit buttons and `SelectReconSpec` message |

---

## Task 1: Add Delete/Deleted to PayloadAction

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/PayloadAction.scala`

- [ ] **Step 1: Add Delete and Deleted cases**

Replace the entire file content:

```scala
package perfectpair.util

enum PayloadAction:
  case Update
  case Updated
  case Create
  case Created
  case Delete
  case Deleted
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS (or only pre-existing errors unrelated to PayloadAction)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/util/PayloadAction.scala
git commit -m "feat: add Delete and Deleted cases to PayloadAction"
```

---

## Task 2: Create MaintainReconSpecView.scala

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction}
import perfectpair.view.components.{Buttons, Form}
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MaintainReconSpecView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(reconSpec = Some(spec)), Cmd.None)
      case Msg.ReconSpecAction(spec, Create) =>
        (model, HttpHelper.createReconSpec(spec))
      case Msg.ReconSpecAction(spec, Created) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(spec, Update) =>
        (model, HttpHelper.updateReconSpec(spec))
      case Msg.ReconSpecAction(spec, Updated) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(_, Delete) =>
        val id = model.reconSpec.flatMap(_.id).getOrElse("")
        (model, HttpHelper.deleteReconSpec(id))
      case Msg.ReconSpecAction(_, Deleted) =>
        (model, Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))

  private def movementSpecSelect(
    fieldID:    String,
    labelName:  String,
    selectedId: String,
    specs:      Seq[MovementSpecPayload],
    onSelect:   String => AppMsg
  ): Html[AppMsg] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(labelName)),
      select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onSelect)
      )(
        specs.map(spec =>
          option(
            value := spec.id.getOrElse(""),
            if spec.id.getOrElse("") == selectedId then attribute("selected", "selected") else EmptyAttribute
          )(text(spec.name))
        )*
      )
    )

  def apply(model: Model): Html[AppMsg] =
    val firstSpecId = model.movementSpecs.headOption.flatMap(_.id).getOrElse("")
    val spec = model.reconSpec.getOrElse(
      ReconciliationSpecPayload(
        accountId         = model.account.id,
        inMovementSpecId  = firstSpecId,
        outMovementSpecId = firstSpecId
      )
    )
    val action = if spec.id.isEmpty then Create else Update
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Recon Spec")),
        Form.field[AppMsg](
          fieldID      = "description",
          labelName    = "Description",
          defaultValue = Some(spec.description),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(description = value))))
        ),
        Form.field[AppMsg](
          fieldID      = "agingLimit",
          labelName    = "Aging Limit",
          defaultValue = Some(spec.agingLimit.toString),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(agingLimit = value.toIntOption.getOrElse(0)))))
        ),
        Form.field[AppMsg](
          fieldID      = "relationship",
          labelName    = "Relationship",
          defaultValue = Some(spec.relationship),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(relationship = value))))
        ),
        movementSpecSelect(
          fieldID    = "inMovementSpecId",
          labelName  = "In Movement Spec",
          selectedId = spec.inMovementSpecId,
          specs      = model.movementSpecs,
          onSelect   = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(inMovementSpecId = value)))
        ),
        movementSpecSelect(
          fieldID    = "outMovementSpecId",
          labelName  = "Out Movement Spec",
          selectedId = spec.outMovementSpecId,
          specs      = model.movementSpecs,
          onSelect   = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(outMovementSpecId = value)))
        ),
        div(cls := "flex justify-between mt-6")(
          if spec.id.isDefined then
            Buttons.textButton("Delete", AppMsg.MaintainReconSpecMsg(Msg.ReconSpecAction(spec, Delete)))
          else
            div()(),
          div(cls := "flex gap-2")(
            Buttons.textButton("Cancel", AppMsg.NavigateToReconSpecs(model.account)),
            Buttons.linkButton("Save", AppMsg.MaintainReconSpecMsg(Msg.ReconSpecAction(spec, action)))
          )
        )
      )
    )

  enum Msg:
    case FormUpdate(spec: ReconciliationSpecPayload)
    case ReconSpecAction(spec: ReconciliationSpecPayload, action: PayloadAction)

  case class Model(
    account:       AccountPayload                    = AccountPayload(),
    movementSpecs: Seq[MovementSpecPayload]          = Seq(),
    reconSpec:     Option[ReconciliationSpecPayload] = None
  )
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS (unresolved `HttpHelper` references are expected at this stage — they will be added in Task 3)

> Note: If the compiler errors on `HttpHelper.createReconSpec` etc., temporarily stub those calls with `Cmd.None` until Task 3 is complete, then restore.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala
git commit -m "feat: add MaintainReconSpecView form page for recon spec CRUD"
```

---

## Task 3: Add HTTP helpers for recon spec CRUD

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`

Three changes: (a) add `MaintainReconSpecView` to the import, (b) add three HTTP command methods, (c) add two decoders inside `ResponseDecoders`.

- [ ] **Step 1: Add import for MaintainReconSpecView**

In `HttpHelper.scala`, find the existing import line:

```scala
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView, ReconSpecsView, Welcome}
```

Replace it with:

```scala
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MaintainReconSpecView, MovementSpecsView, ReconSpecsView, Welcome}
```

- [ ] **Step 2: Add delete private helper method**

After the existing `private def put[A]` method (around line 66), add:

```scala
  private def delete[A](url: String): Request[A] =
    Request(
      method = Method.Delete,
      url    = url,
      body   = Body.Empty
    )
```

- [ ] **Step 3: Add createReconSpec, updateReconSpec, deleteReconSpec methods**

After the existing `updateMovementSpec` method (around line 99), add:

```scala
  def createReconSpec(spec: ReconciliationSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(HttpPath.reconSpec), Body.json(write(spec))),
      reconSpecDecoder(Created)
    )

  def updateReconSpec(spec: ReconciliationSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(HttpPath.url(s"${HttpPath.reconSpec}/${spec.id.get}"), Body.json(write(spec))),
      reconSpecDecoder(Updated)
    )

  def deleteReconSpec(id: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      delete(HttpPath.url(s"${HttpPath.reconSpec}/$id")),
      reconSpecDeletedDecoder
    )
```

- [ ] **Step 4: Add reconSpecDecoder and reconSpecDeletedDecoder inside ResponseDecoders**

Inside the `object ResponseDecoders` block, after the existing `reconSpecsMovementSpecsDecoder` val, add:

```scala
    def reconSpecDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val spec = read[ReconciliationSpecPayload](onResponse.body)
          Msg.MaintainReconSpecMsg(MaintainReconSpecView.Msg.ReconSpecAction(spec, action))
        ,
        error => Msg.Error(error.toString)
      )

    val reconSpecDeletedDecoder: Decoder[Msg] =
      Decoder(
        _ => Msg.MaintainReconSpecMsg(MaintainReconSpecView.Msg.ReconSpecAction(ReconciliationSpecPayload(), Deleted)),
        error => Msg.Error(error.toString)
      )
```

Note: `reconSpecDeletedDecoder` ignores the response body (backend returns `"deleted"` plain string). The empty `ReconciliationSpecPayload()` is a placeholder — the `Deleted` handler in `MaintainReconSpecView.update` ignores `spec` entirely and navigates using `model.account`.

- [ ] **Step 5: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/scala/perfectpair/util/HttpHelper.scala
git commit -m "feat: add HTTP helpers and decoders for recon spec CRUD"
```

---

## Task 4: Update model.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`

Three additions: a navigation message, a page message wrapper, and a model field.

- [ ] **Step 1: Add NavigateToMaintainReconSpec and MaintainReconSpecMsg to the Msg enum**

In `model.scala`, find the navigation messages section:

```scala
  case NavigateToReconSpecs(account: AccountPayload)
```

Add after it:

```scala
  case NavigateToMaintainReconSpec(account: AccountPayload, movementSpecs: Seq[MovementSpecPayload], reconSpec: Option[ReconciliationSpecPayload])
```

Then find the page message wrappers section:

```scala
  case ReconSpecsMsg(msg: ReconSpecsView.Msg)
```

Add after it:

```scala
  case MaintainReconSpecMsg(msg: MaintainReconSpecView.Msg)
```

- [ ] **Step 2: Add MaintainReconSpecView import and ReconciliationSpecPayload import**

At the top of `model.scala`, the existing import is:

```scala
import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
```

Replace with:

```scala
import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload, ReconciliationSpecPayload}
```

And find:

```scala
import perfectpair.view.pages.*
```

This wildcard import already covers `MaintainReconSpecView`, so no change needed there.

- [ ] **Step 3: Add maintainReconSpecModel field to the Model case class**

Find the Model case class fields:

```scala
  reconSpecsModel:             ReconSpecsView.Model            = ReconSpecsView.Model(),
```

Add after it:

```scala
  maintainReconSpecModel:      MaintainReconSpecView.Model     = MaintainReconSpecView.Model(),
```

Also add it to `Model.init` at the bottom of the file:

```scala
    reconSpecsModel            = ReconSpecsView.Model()
```

Add after it:

```scala
    maintainReconSpecModel     = MaintainReconSpecView.Model()
```

- [ ] **Step 4: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/perfectpair/model/model.scala
git commit -m "feat: wire MaintainReconSpecView into global Msg and Model"
```

---

## Task 5: Add MaintainReconSpec page to Page.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`

- [ ] **Step 1: Add MaintainReconSpec page object**

Find the existing `ReconSpecs` page object (around line 133) and add the new page after it:

```scala
  object MaintainReconSpec extends Page {
    val path:   String             = "/components/maintain-recon-spec"
    val render: Model => Html[Msg] = m => MaintainReconSpecView(m.maintainReconSpecModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainReconSpecMsg(sub) =>
        val (sub2, cmd) = MaintainReconSpecView.update(model.maintainReconSpecModel, sub)
        (model.copy(maintainReconSpecModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Recon Spec page")
  }
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/page/Page.scala
git commit -m "feat: add MaintainReconSpec page to Page"
```

---

## Task 6: Handle navigation in WebApp.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Add MaintainReconSpecView import**

Find the existing imports line:

```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView, ReconSpecsView}
```

Replace with:

```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MaintainReconSpecView, MovementSpecsView, ReconSpecsView}
```

- [ ] **Step 2: Add NavigateToMaintainReconSpec handler**

Find the existing `NavigateToReconSpecs` handler in `WebApp.update`:

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

Add the following case after it:

```scala
    case Msg.NavigateToMaintainReconSpec(account, movementSpecs, reconSpec) =>
      (
        model.copy(maintainReconSpecModel = MaintainReconSpecView.Model(
          account       = account,
          movementSpecs = movementSpecs,
          reconSpec     = reconSpec
        )),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainReconSpec))
      )
```

- [ ] **Step 3: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/main/WebApp.scala
git commit -m "feat: handle NavigateToMaintainReconSpec in WebApp"
```

---

## Task 7: Add Add/Edit buttons to ReconSpecsView

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala`

- [ ] **Step 1: Add SelectReconSpec message and update handler**

Replace the current `ReconSpecsView.scala` with:

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.components.{Buttons, Table}
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
      case Msg.SelectReconSpec(reconSpec) =>
        (model, Cmd.emit(AppMsg.NavigateToMaintainReconSpec(model.account, model.movementSpecs, reconSpec)))

  def apply(model: Model): Html[AppMsg] =
    val specNameById = model.movementSpecs.flatMap(ms => ms.id.map(_ -> ms.name)).toMap
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Recon Spec", AppMsg.ReconSpecsMsg(Msg.SelectReconSpec(None))),
        Table.create(
          Seq("Description", "Aging Limit", "Relationship", "In Movement Spec", "Out Movement Spec", ""),
          model.reconSpecs
            .sortBy(_.description)
            .map(spec =>
              Seq(
                text(spec.description),
                text(spec.agingLimit.toString),
                text(spec.relationship),
                text(specNameById.getOrElse(spec.inMovementSpecId, spec.inMovementSpecId)),
                text(specNameById.getOrElse(spec.outMovementSpecId, spec.outMovementSpecId)),
                Buttons.textButton("Edit", AppMsg.ReconSpecsMsg(Msg.SelectReconSpec(Some(spec))))
              )
            )
        )
      )
    )

  enum Msg:
    case ReconSpecsFromBackend(specs: Seq[ReconciliationSpecPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case SelectReconSpec(reconSpec: Option[ReconciliationSpecPayload])

  case class Model(
    account:       AccountPayload                 = AccountPayload(),
    reconSpecs:    Seq[ReconciliationSpecPayload] = Seq(),
    movementSpecs: Seq[MovementSpecPayload]       = Seq()
  )
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/ReconSpecsView.scala
git commit -m "feat: add Add/Edit buttons to ReconSpecsView with navigation to MaintainReconSpecView"
```

---

## Self-Review

**Spec coverage check:**
- ✅ Create recon spec — Task 2 (form), Task 3 (HTTP), Task 6 (navigation)
- ✅ Update recon spec — Task 2 (form, Edit button), Task 3 (HTTP)
- ✅ Delete recon spec — Task 1 (PayloadAction), Task 2 (Delete button), Task 3 (HTTP)
- ✅ Dropdowns for inMovementSpecId/outMovementSpecId — Task 2 (`movementSpecSelect`)
- ✅ Called from ReconSpecsView — Task 7 (Add/Edit buttons)
- ✅ rule omitted — confirmed absent from Task 2 form
- ✅ Back-navigation to ReconSpecsView after save/delete — Task 2 update handlers

**Placeholder scan:** None found.

**Type consistency:**
- `MaintainReconSpecView.Msg.ReconSpecAction` used consistently in Tasks 2, 3
- `MaintainReconSpecView.Model` fields `account`, `movementSpecs`, `reconSpec` match usage in Tasks 4, 5, 6
- `Msg.NavigateToMaintainReconSpec` signature `(account, movementSpecs, reconSpec)` matches handler in Task 6 and caller in Task 7
- `PayloadAction.Delete`/`Deleted` added in Task 1, used in Tasks 2 and 3
