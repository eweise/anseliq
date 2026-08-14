# Reconciliations Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Reconciliations page that displays reconciliation records for a recon spec, navigable via a new "Reconciliations" button on each row of the Recon Specs view.

**Architecture:** Follows the `UnreconciledMovementsView` pattern exactly — a new view file, wired into the existing Tyrian model/page/route/WebApp plumbing, with a fetch on `beforeEnter`. The Recon Specs view gains a new Msg case and button per row.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-style FRP), Tailwind CSS, upickle, ZIO

---

## Files

- **Create:** `frontend/src/main/scala/onparity/view/pages/ReconciliationsView.scala`
- **Modify:** `frontend/src/main/scala/onparity/util/HttpHelper.scala` — add fetch fn + decoder
- **Modify:** `frontend/src/main/scala/onparity/model/model.scala` — add Msg cases + Model field
- **Modify:** `frontend/src/main/scala/onparity/page/Page.scala` — add `Page.Reconciliations`
- **Modify:** `frontend/src/main/scala/onparity/route/route.scala` — add route entry
- **Modify:** `frontend/src/main/scala/onparity/main/WebApp.scala` — handle navigation msg
- **Modify:** `frontend/src/main/scala/onparity/view/pages/ReconSpecsView.scala` — add button + msg

---

### Task 1: Create ReconciliationsView

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/ReconciliationsView.scala`

No automated frontend tests exist; compilation + manual browser testing is the verification approach.

- [ ] **Step 1: Create the view file**

```scala
package onparity.view.pages

import onparity.common.model.{AccountPayload, ReconciliationPayload, ReconciliationSpecPayload}
import onparity.model.Msg as AppMsg
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object ReconciliationsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.ReconciliationsFromBackend(reconciliations) =>
        (model.copy(reconciliations = reconciliations), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(s"${model.reconSpec.description} — Reconciliations")
        ),
        Buttons.linkButton("Back to Recon Specs", AppMsg.NavigateToReconSpecs(model.account)),
        Table.create(
          Seq("Description", "Status", "Applied Rules", "Created At", "Updated At"),
          model.reconciliations.map(r =>
            Seq(
              text(r.description),
              text(r.status),
              text(r.appliedRules),
              text(ViewHelper.formatDateTime(r.createdAt)),
              text(ViewHelper.formatDateTime(r.updatedAt))
            )
          )
        )
      )
    )

  enum Msg:
    case ReconciliationsFromBackend(reconciliations: Seq[ReconciliationPayload])

  case class Model(
    account:         AccountPayload               = AccountPayload(),
    reconSpec:       ReconciliationSpecPayload    = ReconciliationSpecPayload(),
    reconciliations: Seq[ReconciliationPayload]   = Seq()
  )
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ReconciliationsView.scala
git commit -m "feat: add ReconciliationsView"
```

---

### Task 2: Wire HTTP, model, page, and route plumbing

**Files:**
- Modify: `frontend/src/main/scala/onparity/util/HttpHelper.scala`
- Modify: `frontend/src/main/scala/onparity/model/model.scala`
- Modify: `frontend/src/main/scala/onparity/page/Page.scala`
- Modify: `frontend/src/main/scala/onparity/route/route.scala`
- Modify: `frontend/src/main/scala/onparity/main/WebApp.scala`

These five files must all be updated before the project will compile.

- [ ] **Step 1: Add `reconciliationsDecoder` to `HttpHelper.ResponseDecoders`**

In `HttpHelper.scala`, add after the `reconcileDecoder` method (at the bottom of `object ResponseDecoders`):

```scala
    val reconciliationsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val reconciliations = read[Seq[ReconciliationPayload]](onResponse.body)
          Msg.ReconciliationsMsg(ReconciliationsView.Msg.ReconciliationsFromBackend(reconciliations))
        ,
        error => Msg.Error(error.toString)
      )
```

- [ ] **Step 2: Add `fetchReconciliationsByReconSpecID` to `HttpHelper`**

In `HttpHelper.scala`, add after `reconcileAccount`:

```scala
  def fetchReconciliationsByReconSpecID(reconSpecId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.reconSpec}/$reconSpecId/reconciliations"),
      ResponseDecoders.reconciliationsDecoder
    )
```

- [ ] **Step 3: Add new Msg cases to `model.scala`**

In `model.scala`, add to the `Msg` enum after `NavigateToUnreconciledMovements`:

```scala
  case NavigateToReconciliations(reconSpec: ReconciliationSpecPayload, account: AccountPayload)
```

Add after `UnreconciledMovementsMsg`:

```scala
  case ReconciliationsMsg(msg: ReconciliationsView.Msg)
```

- [ ] **Step 4: Add `reconciliationsModel` field to `Model` in `model.scala`**

Add to the `Model` case class after `toggleModel`:

```scala
  reconciliationsModel:       ReconciliationsView.Model       = ReconciliationsView.Model(),
```

The full `Model` case class should look like:

```scala
case class Model(
  currentPage:                Page,
  user:                       Option[User],
  isDarkMode:                 Boolean,
  loginModel:                 LoginPage.Model,
  homeModel:                  Welcome.Model,
  fundFlowsModel:             FundFlowsView.Model,
  maintainFundFlowModel:      MaintainFundFlowView.Model,
  accountsModel:              AccountsView.Model,
  maintainAccountModel:       MaintainAccountView.Model,
  movementSpecsModel:         MovementSpecsView.Model,
  maintainMovementSpecModel:  MaintainMovementSpecsView.Model = MaintainMovementSpecsView.Model(),
  reconSpecsModel:            ReconSpecsView.Model            = ReconSpecsView.Model(),
  maintainReconSpecModel:     MaintainReconSpecView.Model     = MaintainReconSpecView.Model(),
  ingestModel:                IngestView.Model                = IngestView.Model(),
  jobsModel:                  JobsView.Model                  = JobsView.Model(),
  unreconciledMovementsModel: UnreconciledMovementsView.Model = UnreconciledMovementsView.Model(),
  toggleModel:                ToggleView.Model                = ToggleView.Model(),
  reconciliationsModel:       ReconciliationsView.Model       = ReconciliationsView.Model(),
  error:                      Option[String]                  = None
)
```

- [ ] **Step 5: Add `Page.Reconciliations` to `Page.scala`**

Add before the final closing `}` of `object Page`:

```scala
  object Reconciliations extends Page {
    val path:   String             = "/components/reconciliations"
    val render: Model => Html[Msg] = m => ReconciliationsView(m.reconciliationsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.ReconciliationsMsg(sub) =>
        val (sub2, cmd) = ReconciliationsView.update(model.reconciliationsModel, sub)
        (model.copy(reconciliationsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Reconciliations page") |+|
        HttpHelper.fetchReconciliationsByReconSpecID(model.reconciliationsModel.reconSpec.id.get)
  }
```

- [ ] **Step 6: Add route entry to `route.scala`**

In `route.scala`, add to the `ComponentDemo.unapply` pattern match after `"unreconciled-movements"`:

```scala
        case "reconciliations"        => Page.Reconciliations
```

The full match block should look like:

```scala
    def unapply(path: String): Option[Page] = Try {
      path.split("/components/")(1) match {
        case "toggle"                 => Page.Toggle
        case "fund-flows"             => Page.FundFlows
        case "maintain-fund-flow"     => Page.MaintainFundFlow
        case "accounts"               => Page.Accounts
        case "maintain-account"       => Page.MaintainAccount
        case "movement-specs"         => Page.MovementSpecs
        case "maintain-movement-spec" => Page.MaintainMovementSpec
        case "recon-specs"            => Page.ReconSpecs
        case "maintain-recon-spec"    => Page.MaintainReconSpec
        case "ingest"                 => Page.Ingest
        case "jobs"                   => Page.Jobs
        case "unreconciled-movements" => Page.UnreconciledMovements
        case "reconciliations"        => Page.Reconciliations
      }
    }.toOption
```

- [ ] **Step 7: Add navigation handler to `WebApp.scala`**

In `WebApp.scala`, add after the `NavigateToUnreconciledMovements` case (around line 158):

```scala
    case Msg.NavigateToReconciliations(reconSpec, account) =>
      (
        model.copy(reconciliationsModel = ReconciliationsView.Model(account = account, reconSpec = reconSpec)),
        Cmd.Emit(Msg.NavigateTo(Page.Reconciliations))
      )
```

- [ ] **Step 8: Verify compilation**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/fastLinkJS"
```

Expected: `[success] Total time: ...` with no errors (pre-existing warnings about pattern match exhaustivity are fine).

- [ ] **Step 9: Commit**

```bash
git add frontend/src/main/scala/onparity/util/HttpHelper.scala \
        frontend/src/main/scala/onparity/model/model.scala \
        frontend/src/main/scala/onparity/page/Page.scala \
        frontend/src/main/scala/onparity/route/route.scala \
        frontend/src/main/scala/onparity/main/WebApp.scala
git commit -m "feat: wire Reconciliations page plumbing"
```

---

### Task 3: Add navigation from ReconSpecsView

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ReconSpecsView.scala`

- [ ] **Step 1: Add `ViewReconciliations` msg and handler to `ReconSpecsView.scala`**

The full updated `ReconSpecsView.scala`:

```scala
package onparity.view.pages

import onparity.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import onparity.model.Msg as AppMsg
import onparity.view.components.{Buttons, Table}
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
      case Msg.RunReconSpec(reconSpec) =>
        (model, Cmd.emit(AppMsg.NavigateToIngest(model.account, reconSpec, model.movementSpecs)))
      case Msg.ViewReconciliations(reconSpec) =>
        (model, Cmd.Emit(AppMsg.NavigateToReconciliations(reconSpec, model.account)))

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
                text(spec.relationship.toString),
                text(specNameById.getOrElse(spec.inMovementSpecId, spec.inMovementSpecId)),
                text(specNameById.getOrElse(spec.outMovementSpecId, spec.outMovementSpecId)),
                div(cls := "flex gap-2")(
                  Buttons.textButton("Ingest Files", AppMsg.ReconSpecsMsg(Msg.RunReconSpec(spec))),
                  Buttons.textButton("Edit", AppMsg.ReconSpecsMsg(Msg.SelectReconSpec(Some(spec)))),
                  Buttons.textButton("Reconciliations", AppMsg.ReconSpecsMsg(Msg.ViewReconciliations(spec)))
                )
              )
            )
        )
      )
    )

  enum Msg:
    case ReconSpecsFromBackend(specs: Seq[ReconciliationSpecPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case SelectReconSpec(reconSpec: Option[ReconciliationSpecPayload])
    case RunReconSpec(reconSpec: ReconciliationSpecPayload)
    case ViewReconciliations(reconSpec: ReconciliationSpecPayload)

  case class Model(
    account:       AccountPayload                 = AccountPayload(),
    reconSpecs:    Seq[ReconciliationSpecPayload] = Seq(),
    movementSpecs: Seq[MovementSpecPayload]       = Seq()
  )
```

- [ ] **Step 2: Verify compilation**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/fastLinkJS"
```

Expected: `[success] Total time: ...` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ReconSpecsView.scala
git commit -m "feat: add Reconciliations navigation button to ReconSpecsView"
```

---

### Task 4: Manual verification

- [ ] **Step 1: Start the backend**

In one terminal:
```bash
cd /Users/eweise/projects/onparity && sbt "backend/run"
```

- [ ] **Step 2: Start the frontend dev server**

In a second terminal:
```bash
cd /Users/eweise/projects/onparity && sbt "frontend/devServer"
```

- [ ] **Step 3: Verify the happy path**

1. Open the app (typically `http://localhost:5173`)
2. Navigate to Fund Flow → Accounts → an Account → Recon Specs
3. Each recon spec row should show a "Reconciliations" button alongside "Ingest Files" and "Edit"
4. Click "Reconciliations" — the page should navigate to `/components/reconciliations`
5. The page title should be `<spec description> — Reconciliations`
6. The table should display columns: Description, Status, Applied Rules, Created At, Updated At
7. "Back to Recon Specs" should return to the Recon Specs page
