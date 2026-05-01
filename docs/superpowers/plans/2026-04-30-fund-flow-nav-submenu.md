# Fund Flow Nav Submenu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an accordion submenu under "Fund Flows" in the left nav listing individual fund flow names, each linking to a new `FundFlowView` detail page; fund flows are fetched on session restore so the list is always populated.

**Architecture:** `FundFlowView` is a new minimal view (name only, extensible later). `Model` gains two new fields: `fundFlowNavOpen: Boolean` (accordion state) and `fundFlowViewModel: FundFlowView.Model` (carries the selected fund flow). `HttpHelper.fetchFundFlows` is emitted in the `Msg.RestoredSession` handler so the sidebar list is populated immediately after auth. `MainContainer.leftNav` is updated to render the accordion using these model fields.

**Tech Stack:** Scala 3, Tyrian (Elm-like ScalaJS UI framework), Tailwind CSS. Build: `sbt "frontend/fastOptJS"` compiles to JS consumed by Vite dev server (`npm run dev` inside `frontend/`, served at `http://localhost:9876`).

---

## File Map

| Action | File |
|--------|------|
| **Create** | `frontend/src/main/scala/perfectpair/view/pages/FundFlowView.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/model/model.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/page/Page.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/route/route.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/main/WebApp.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/view/MainContainer.scala` |

---

### Task 1: Create FundFlowView

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/FundFlowView.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg as AppMsg
import tyrian.Html
import tyrian.Html.*

object FundFlowView:

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(model.fundFlow.name)
        )
      )
    )

  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())
```

- [ ] **Step 2: Compile to verify no errors**

Run from the project root:
```bash
sbt "frontend/fastOptJS"
```
Expected: `[success]` with no compilation errors. (The rest of the app still compiles because nothing references `FundFlowView` yet.)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/FundFlowView.scala
git commit -m "feat: add FundFlowView page stub"
```

---

### Task 2: Extend Model with new fields and messages

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/model/model.scala`

The `Msg` enum currently lives in this file. `FundFlowView` is already available via `import perfectpair.view.pages.*` at line 6.

- [ ] **Step 1: Add two new messages to the `Msg` enum**

In `model.scala`, after line 51 (`case NavigateToReconciliations(...)`), add:

```scala
  case ToggleFundFlowNav
  case NavigateToFundFlow(ff: FundFlowPayload)
```

- [ ] **Step 2: Add two new fields to the `Model` case class**

In the `Model` case class (around line 91), add after `error: Option[String] = None`:

```scala
  fundFlowNavOpen:    Boolean            = false,
  fundFlowViewModel:  FundFlowView.Model = FundFlowView.Model(),
```

The full updated field list for `Model` (showing only the changed/added lines in context):

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
  error:                      Option[String]                  = None,
  fundFlowNavOpen:            Boolean                         = false,
  fundFlowViewModel:          FundFlowView.Model              = FundFlowView.Model()
):
```

- [ ] **Step 3: Compile to verify**

```bash
sbt "frontend/fastOptJS"
```
Expected: `[success]`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/model/model.scala
git commit -m "feat: add fundFlowNavOpen, fundFlowViewModel, ToggleFundFlowNav, NavigateToFundFlow to model"
```

---

### Task 3: Add Page.FundFlow and route

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/page/Page.scala`
- Modify: `frontend/src/main/scala/perfectpair/route/route.scala`

- [ ] **Step 1: Add `Page.FundFlow` to Page.scala**

In `Page.scala`, after the closing `}` of `object Reconciliations` (around line 231), and before the closing `}` of `object Page`, add:

```scala
  object FundFlow extends Page {
    val path:   String             = "/components/fund-flow"
    val render: Model => Html[Msg] = m => FundFlowView(m.fundFlowViewModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) =
      (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Fund Flow page")
  }
```

- [ ] **Step 2: Add route for `fund-flow` in route.scala**

In `route.scala`, in the `ComponentDemo.unapply` match block, add after `case "reconciliations" => Page.Reconciliations`:

```scala
        case "fund-flow" => Page.FundFlow
```

- [ ] **Step 3: Compile to verify**

```bash
sbt "frontend/fastOptJS"
```
Expected: `[success]`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/page/Page.scala \
        frontend/src/main/scala/perfectpair/route/route.scala
git commit -m "feat: add Page.FundFlow and fund-flow route"
```

---

### Task 4: Update WebApp — new message handlers and early fund flow fetch

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

Three changes: (a) handle `ToggleFundFlowNav`, (b) handle `NavigateToFundFlow`, (c) fetch fund flows on `RestoredSession`, (d) pass new args to `MainContainer` in `view`.

- [ ] **Step 1: Handle `Msg.ToggleFundFlowNav`**

In `WebApp.update`, after the `case Msg.Logout => ...` block (around line 68), add:

```scala
    case Msg.ToggleFundFlowNav =>
      (model.copy(fundFlowNavOpen = !model.fundFlowNavOpen), Cmd.None)
```

- [ ] **Step 2: Handle `Msg.NavigateToFundFlow`**

After the `Msg.NavigateToMaintainFundFlow` block (around line 83), add:

```scala
    case Msg.NavigateToFundFlow(ff) =>
      (
        model.copy(fundFlowViewModel = FundFlowView.Model(ff)),
        Cmd.Emit(Msg.NavigateTo(Page.FundFlow))
      )
```

Also add the import for `FundFlowView` at the top of the file (it is already covered by `import perfectpair.view.pages.*` on line 8 — no change needed).

- [ ] **Step 3: Fetch fund flows on session restore**

Replace the existing `Msg.RestoredSession` handler (line 64–65):

```scala
    // BEFORE:
    case Msg.RestoredSession(accessToken, refreshToken) =>
      (model.loginSuccess(accessToken, refreshToken), Cmd.Emit(Msg.DoNavigate(Page.Home)))
```

with:

```scala
    // AFTER:
    case Msg.RestoredSession(accessToken, refreshToken) =>
      (
        model.loginSuccess(accessToken, refreshToken),
        HttpHelper.fetchFundFlows |+| Cmd.Emit(Msg.DoNavigate(Page.Home))
      )
```

- [ ] **Step 4: Pass new args to MainContainer in `view`**

Replace line 177:

```scala
    // BEFORE:
    MainContainer(pageContent, model.isDarkMode, model.isLoggedIn, model.error)
```

with:

```scala
    // AFTER:
    MainContainer(pageContent, model.isDarkMode, model.isLoggedIn, model.error, model.fundFlowsModel.fundFlows, model.fundFlowNavOpen)
```

- [ ] **Step 5: Proceed to Task 5**

Do not compile or commit yet — `MainContainer` still has the old signature, so the build will fail until both files are updated. Continue directly to Task 5.

---

### Task 5: Update MainContainer — accordion UI

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/MainContainer.scala`

- [ ] **Step 1: Add import for FundFlowPayload**

At the top of `MainContainer.scala`, after the existing imports, add:

```scala
import perfectpair.common.model.FundFlowPayload
```

- [ ] **Step 2: Update `apply` signature**

Replace the existing `apply` signature (line 25):

```scala
  // BEFORE:
  def apply(content: Html[Msg], isDark: Boolean, isLoggedIn: Boolean, error: Option[String] = None): Html[Msg] =
    div(cls := (if isDark then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"))(
      navbar(isDark, isLoggedIn),
      leftNav(),
      error.map(errorBanner).getOrElse(div()()),
      main(cls := "flex flex-col min-h-screen mt-20 ml-64 bg-md-surface-variant dark:bg-gray-900")(content),
      theFooter
    )
```

with:

```scala
  // AFTER:
  def apply(content: Html[Msg], isDark: Boolean, isLoggedIn: Boolean, error: Option[String] = None, fundFlows: Seq[FundFlowPayload] = Seq(), fundFlowNavOpen: Boolean = false): Html[Msg] =
    div(cls := (if isDark then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"))(
      navbar(isDark, isLoggedIn),
      leftNav(fundFlows, fundFlowNavOpen),
      error.map(errorBanner).getOrElse(div()()),
      main(cls := "flex flex-col min-h-screen mt-20 ml-64 bg-md-surface-variant dark:bg-gray-900")(content),
      theFooter
    )
```

- [ ] **Step 3: Update `leftNav` signature and body**

Replace the existing `leftNav()` function (lines 78–98) with:

```scala
  private def leftNav(fundFlows: Seq[FundFlowPayload], fundFlowNavOpen: Boolean): Html[Msg] =
    aside(
      cls := "fixed left-0 top-20 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto"
    )(
      nav(cls := "p-4")(
        ul(cls := "space-y-2")(
          navItem("Home", Page.Home, Icons.home),
          navItem("Components", Page.Toggle, Icons.component),
          fundFlowNavSection(fundFlows, fundFlowNavOpen),
          navItem("Create Fund Flow", Page.MaintainFundFlow, Icons.component),
          navItem("Jobs", Page.Jobs, Icons.document),
          navSeparator(),
          navItemExternal("Dashboard", "#", Icons.dashboard),
          navItemExternal("Profile", "#", Icons.user),
          navItemExternal("Settings", "#", Icons.settings),
          navSeparator(),
          navItemExternal("Analytics", "#", Icons.chart),
          navItemExternal("Reports", "#", Icons.document)
        )
      )
    )
```

- [ ] **Step 4: Add `fundFlowNavSection` helper**

After the `navSeparator` function (after line 127 in the original), add:

```scala
  private def fundFlowNavSection(fundFlows: Seq[FundFlowPayload], isOpen: Boolean): Html[Msg] =
    li()(
      div(cls := "flex items-center justify-between rounded-lg text-gray-700 dark:text-gray-200")(
        a(
          href    := Page.FundFlows.path,
          onClick(Msg.NavigateTo(Page.FundFlows)),
          cls     := "flex items-center space-x-3 flex-1 px-4 py-3 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200 rounded-lg"
        )(
          span(cls := "w-5 h-5")(Icons.component),
          span()(text("Fund Flows"))
        ),
        button(
          onClick(Msg.ToggleFundFlowNav),
          cls := "px-3 py-3 rounded hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200 text-gray-500 dark:text-gray-400"
        )(
          text(if isOpen then "▼" else "▶")
        )
      ),
      if isOpen then
        ul(cls := "ml-6 mt-1 space-y-1")(
          fundFlows.map(ff =>
            li()(
              a(
                href    := Page.FundFlow.path,
                onClick(Msg.NavigateToFundFlow(ff)),
                cls     := "block px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-purple-100 dark:hover:bg-purple-900 rounded-lg transition-colors duration-200"
              )(text(ff.name))
            )
          )*
        )
      else
        ul()()
    )
```

- [ ] **Step 5: Compile both WebApp and MainContainer together**

```bash
sbt "frontend/fastOptJS"
```
Expected: `[success]` with no errors.

- [ ] **Step 6: Commit both files together**

```bash
git add frontend/src/main/scala/perfectpair/main/WebApp.scala \
        frontend/src/main/scala/perfectpair/view/MainContainer.scala
git commit -m "feat: add accordion fund flow submenu to left nav"
```

---

### Task 6: Browser verification

- [ ] **Step 1: Start the Scala.js watch compiler** (in a separate terminal)

```bash
sbt "~frontend/fastOptJS"
```

- [ ] **Step 2: Start the Vite dev server** (in another terminal, from `frontend/`)

```bash
cd frontend && npm run dev
```
Open `http://localhost:9876` in a browser.

- [ ] **Step 3: Verify accordion toggle**

1. Log in (or let session restore run).
2. Look at the left nav — "Fund Flows" row should show a `▶` chevron on the right.
3. Click the `▶` chevron — the submenu should expand showing fund flow names (fetched on login), chevron flips to `▼`.
4. Click `▼` — submenu collapses again.

- [ ] **Step 4: Verify navigation**

1. Expand the submenu and click a fund flow name.
2. Browser should navigate to `/components/fund-flow` and the main area should show that fund flow's name as an `h2`.
3. Click "Fund Flows" label (left part of the row) — should navigate to `/components/fund-flows` (the list page), submenu state unchanged.

- [ ] **Step 5: Verify fund flows load on session restore**

1. Hard-refresh the page (Cmd+Shift+R / Ctrl+Shift+R) — session is restored from localStorage.
2. Without visiting the Fund Flows list page, expand the accordion — fund flow names should be visible immediately.

- [ ] **Step 6: Final commit if any tweaks were made**

```bash
git add -p
git commit -m "fix: nav submenu tweaks from browser testing"
```
(Only needed if you made any visual adjustments during testing.)
