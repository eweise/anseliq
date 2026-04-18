# MaintainMovementSpecsView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `MaintainMovementSpecsView` page for creating and editing movement specs, reachable from `MovementSpecsView` via an Edit button per row and a Create button.

**Architecture:** Follows the existing `MaintainAccountView` pattern exactly. Navigation carries `fundFlow`, `accounts`, and optional `movementSpec` via `AppMsg.NavigateToMaintainMovementSpec`. The accounts list (already fetched in `MovementSpecsView`) is stored in that page's model and passed along on navigation. `inAccountId`/`outAccountId` are rendered as dropdowns.

**Tech Stack:** Scala 3.8.1, Scala.js, Tyrian (Elm-style TEA), ZIO, upickle

---

## Files

- Create: `frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala`
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala`

---

### Task 1: Create MaintainMovementSpecsView

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction, PrettyLogger}
import perfectpair.view.components.{Buttons, Form}
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MaintainMovementSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("movement spec form modified"))
      case Msg.MovementSpecAction(spec, Create) =>
        (model, HttpHelper.createMovementSpec(spec) |+| PrettyLogger.info("creating movement spec"))
      case Msg.MovementSpecAction(spec, Created) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("created movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow)))
      case Msg.MovementSpecAction(spec, Update) =>
        (model, HttpHelper.updateMovementSpec(spec) |+| PrettyLogger.info("updating movement spec"))
      case Msg.MovementSpecAction(spec, Updated) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("updated movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow)))

  private def accountSelect(
    fieldID:    String,
    labelName:  String,
    selectedId: String,
    accounts:   Seq[AccountPayload],
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
        accounts.map(account =>
          option(
            value := account.id.getOrElse(""),
            if account.id.getOrElse("") == selectedId then attribute("selected", "selected") else EmptyAttribute
          )(text(account.name))
        )*
      )
    )

  def apply(model: Model): Html[AppMsg] =
    val spec   = model.movementSpec.getOrElse(MovementSpecPayload())
    val action = if spec.id.isEmpty then Create else Update
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Movement Spec")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(spec.name),
          onChangFn    = Some(value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(name = value))))
        ),
        accountSelect(
          fieldID    = "inAccountId",
          labelName  = "In Account",
          selectedId = spec.inAccountId,
          accounts   = model.accounts,
          onSelect   = value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(inAccountId = value)))
        ),
        accountSelect(
          fieldID    = "outAccountId",
          labelName  = "Out Account",
          selectedId = spec.outAccountId,
          accounts   = model.accounts,
          onSelect   = value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(outAccountId = value)))
        ),
        Form.field[AppMsg](
          fieldID      = "dataSourceJson",
          labelName    = "Data Source JSON",
          defaultValue = Some(spec.dataSourceJson),
          onChangFn    = Some(value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(dataSourceJson = value))))
        ),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.linkButton("Save", AppMsg.MaintainMovementSpecsMsg(Msg.MovementSpecAction(spec, action)))
        )
      )
    )

  enum Msg:
    case FormUpdate(spec: MovementSpecPayload)
    case MovementSpecAction(spec: MovementSpecPayload, action: PayloadAction)

  case class Model(
    fundFlow:     FundFlowPayload             = FundFlowPayload(),
    accounts:     Seq[AccountPayload]         = Seq(),
    movementSpec: Option[MovementSpecPayload] = None
  )
```

---

### Task 2: Update model.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`

- [ ] **Step 1: Add `NavigateToMaintainMovementSpec` to the AppMsg enum**

After the `NavigateToMaintainAccount` line, add:

```scala
  case NavigateToMaintainMovementSpec(fundFlow: FundFlowPayload, accounts: Seq[AccountPayload], movementSpec: Option[MovementSpecPayload])
```

- [ ] **Step 2: Add `MaintainMovementSpecsMsg` to the AppMsg enum**

After the `MovementSpecsMsg` line, add:

```scala
  case MaintainMovementSpecsMsg(msg: MaintainMovementSpecsView.Msg)
```

- [ ] **Step 3: Add `maintainMovementSpecModel` to the Model case class**

After the `movementSpecsModel` field, add:

```scala
  maintainMovementSpecModel: MaintainMovementSpecsView.Model = MaintainMovementSpecsView.Model(),
```

- [ ] **Step 4: Add `maintainMovementSpecModel` to `Model.init`**

After the `movementSpecsModel = MovementSpecsView.Model()` line in `Model.init`, add:

```scala
    maintainMovementSpecModel = MaintainMovementSpecsView.Model()
```

---

### Task 3: Update Page.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`

- [ ] **Step 1: Add `Page.MaintainMovementSpec`**

After the `Page.MovementSpecs` object, add:

```scala
  object MaintainMovementSpec extends Page {
    val path:   String             = "/components/maintain-movement-spec"
    val render: Model => Html[Msg] = m => MaintainMovementSpecsView(m.maintainMovementSpecModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainMovementSpecsMsg(sub) =>
        val (sub2, cmd) = MaintainMovementSpecsView.update(model.maintainMovementSpecModel, sub)
        (model.copy(maintainMovementSpecModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Movement Spec page")
  }
```

---

### Task 4: Update WebApp.scala

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Add `MaintainMovementSpecsView` to the import**

Change the import line:
```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MovementSpecsView}
```
to:
```scala
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView, MaintainMovementSpecsView, MovementSpecsView}
```

- [ ] **Step 2: Add handler for `NavigateToMaintainMovementSpec`**

After the `Msg.NavigateToMovementSpecs` case, add:

```scala
    case Msg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec) =>
      (
        model.copy(maintainMovementSpecModel = MaintainMovementSpecsView.Model(fundFlow, accounts, movementSpec)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainMovementSpec))
      )
```

---

### Task 5: Update HttpHelper

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/util/HttpHelper.scala`

- [ ] **Step 1: Add `createMovementSpec` and `updateMovementSpec` functions**

After `fetchAccountsForMovementSpecs`, add:

```scala
  def createMovementSpec(spec: MovementSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(HttpPath.movementSpec), Body.json(write(spec))),
      movementSpecDecoder(Created)
    )

  def updateMovementSpec(spec: MovementSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(HttpPath.url(s"${HttpPath.movementSpec}/${spec.id.get}"), Body.json(write(spec))),
      movementSpecDecoder(Updated)
    )
```

Note: `Created` and `Updated` come from `perfectpair.util.PayloadAction.*` — add this import if not already present:
```scala
import perfectpair.util.PayloadAction.*
```

- [ ] **Step 2: Add `movementSpecDecoder` to `ResponseDecoders`**

After `movementSpecsAccountsDecoder`, add:

```scala
    def movementSpecDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val spec = read[MovementSpecPayload](onResponse.body)
          Msg.MaintainMovementSpecsMsg(MaintainMovementSpecsView.Msg.MovementSpecAction(spec, action))
        ,
        error => Msg.Error(error.toString)
      )
```

---

### Task 6: Update MovementSpecsView

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala`

- [ ] **Step 1: Replace the full file contents**

```scala
package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecAndAccounts, MovementSpecPayload, zipAccountsAndMovementSpecs}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Table}
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
        (model.copy(accounts = accounts, movementSpecAndAccounts = combined), PrettyLogger.info("zipped accounts and movement specs"))
      case Msg.SelectMovementSpec(msa) =>
        val spec = msa.map(_.spec)
        (model, Cmd.emit(AppMsg.NavigateToMaintainMovementSpec(model.fundFlow, model.accounts, spec)))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Movement Spec", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(None))),
        Table.create(
          Seq("ID", "Name", "In Account", "Out Account", ""),
          model.movementSpecAndAccounts
            .sortBy(_.spec.name)
            .map(msa =>
              Seq(
                text(msa.spec.id.getOrElse("")),
                text(msa.spec.name),
                text(msa.inAccount.name),
                text(msa.outAccount.name),
                Buttons.textButton("Edit", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(Some(msa))))
              )
            )
        )
      )
    )

  enum Msg:
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case SelectMovementSpec(msa: Option[MovementSpecAndAccounts])

  case class Model(
    fundFlow:                FundFlowPayload              = FundFlowPayload(),
    accounts:                Seq[AccountPayload]          = Seq(),
    movementSpecs:           Seq[MovementSpecPayload]     = Seq(),
    movementSpecAndAccounts: Seq[MovementSpecAndAccounts] = Seq()
  )
```

---

### Task 7: Compile and commit

**Files:** All modified files above.

- [ ] **Step 1: Compile**

```bash
sbt "frontend/fastOptJS"
```

Expected: success with no errors.

- [ ] **Step 2: Commit**

```bash
git add \
  frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala \
  frontend/src/main/scala/perfectpair/model/model.scala \
  frontend/src/main/scala/perfectpair/page/Page.scala \
  frontend/src/main/scala/perfectpair/main/WebApp.scala \
  frontend/src/main/scala/perfectpair/util/HttpHelper.scala \
  frontend/src/main/scala/perfectpair/view/pages/MovementSpecsView.scala
git commit -m "feat: add MaintainMovementSpecsView with create/edit and account dropdowns"
```

---

### Task 8: Manual verification

- [ ] **Step 1: Start dev server**

```bash
sbt "frontend/devServer"
```

- [ ] **Step 2: Verify the list view**

Navigate to Fund Flows → click Movement Specs for a fund flow. Verify:
- Table shows an "Edit" button column per row
- An "Add Movement Spec" button appears above the table

- [ ] **Step 3: Verify create**

Click "Add Movement Spec". Verify:
- Form opens with empty Name field and account dropdowns populated from the fund flow's accounts
- Filling in fields and clicking Save posts to the backend and returns to the list

- [ ] **Step 4: Verify edit**

Click "Edit" on an existing row. Verify:
- Form opens pre-populated with the spec's name, selected inAccount, selected outAccount, and dataSourceJson
- Changing fields and clicking Save sends a PUT and returns to the list
