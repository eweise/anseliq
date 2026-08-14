# Frontend Navigation & Design Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix broken/dead navigation and inconsistent look-and-feel across the Laminar frontend by extending the existing Material Design 3 token system (`md-*` in `tailwind.config.js`, established 2026-04-08) to the pages/components that currently bypass it.

**Architecture:** No new architecture — this is a consistency pass over existing files. Delete one orphaned page/route, remove dead nav links, migrate a handful of raw-Tailwind-color usages onto the existing `md-*` tokens, and extract one small shared title component to fix a font-size drift across list pages.

**Tech Stack:** Scala 3, Scala.js, Laminar (`com.raquo.laminar`), Tailwind CSS, Flowbite. No test framework exists for this frontend — verification is `sbt "frontend/Compile/fastOptJS"` (compile check) per task, plus a final manual browser pass.

## Global Constraints

- Do not touch the "Maintain" form pages (`MaintainAccountPage`, `MaintainFundFlowPage`, `MaintainReconSpecPage`, `MaintainMovementSpecPage`, `IngestPage`) — already consistent per the 2026-04-08 spec.
- Do not build real Dashboard/Profile/Settings/Analytics/Reports pages — only remove the dead `"#"` nav stubs pointing to them.
- No backend/API changes.
- No visual redesign beyond consistency (no new palette, no new layout paradigm).
- I have no browser-automation tool available in this environment — final visual verification requires the user to check in an actual browser; I can only guarantee compile-level correctness myself.

---

### Task 1: Clean up LeftNav — remove dead links, migrate to design tokens

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/layout/LeftNav.scala`

**Interfaces:**
- Consumes: nothing new
- Produces: nothing new (no other file references `LeftNav`'s internals beyond `LeftNav()` itself, called from `MainLayout.scala:13`, unaffected by this change)

- [ ] **Step 1: Replace the file contents**

Remove the 5 dead `"#"` nav items (Dashboard, Profile, Settings, Analytics, Reports) and their two `navSeparator()` calls, remove the commented-out `fundFlowNavSection()` line, and migrate the raw `purple-100`/`purple-900` hover colors to the existing `md-primary-container` token (matching `Buttons.scala`'s `textButton` hover convention, which also has no dark-mode-specific override):

```scala
package onparity.view.layout

import com.raquo.laminar.api.L.*
import onparity.routing.Nav
import onparity.state.SessionStore
import onparity.view.components.Icons

object LeftNav:

  def apply(): HtmlElement =
    asideTag(
      cls := "fixed left-0 top-0 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto",
      navTag(
        cls := "p-4",
        ul(
          cls := "space-y-2",
          navItem("Home", "/", Icons.home),
          navItem("Ledgers", "/components/fund-flows", Icons.document),
          navItem("Jobs", "/components/jobs", Icons.document),
          navItem("Events", "/components/events", Icons.document),
          navSeparator(),
          li(
            button(
              cls := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 hover:bg-md-primary-container transition-colors duration-200 w-full text-left",
              onClick --> (_ =>
                SessionStore.clear()
                Nav.push("/login")
              ),
              span(cls := "w-5 h-5", Icons.logout),
              span("Logout")
            )
          )
        )
      )
    )

  private def navItem(lbl: String, itemHref: String, icon: HtmlElement): HtmlElement =
    li(
      a(
        href := itemHref,
        cls  := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-md-primary-container transition-colors duration-200",
        span(cls := "w-5 h-5", icon),
        span(lbl)
      )
    )

  private def navSeparator(): HtmlElement =
    li(cls := "py-2", hr(cls := "border-gray-300 dark:border-gray-600"))
```

- [ ] **Step 2: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/layout/LeftNav.scala
git commit -m "Remove dead nav stubs and migrate LeftNav to design tokens"
```

---

### Task 2: Delete orphaned AccountsPage

**Files:**
- Delete: `frontend/src/main/scala/onparity/view/pages/AccountsPage.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Nav.scala`

**Interfaces:**
- Consumes: nothing
- Produces: nothing (confirmed via grep that `Nav.toAccounts` and `/components/accounts` list route have no other callers/references anywhere in `frontend/src`)

- [ ] **Step 1: Delete the page**

```bash
rm frontend/src/main/scala/onparity/view/pages/AccountsPage.scala
```

- [ ] **Step 2: Remove its route**

In `frontend/src/main/scala/onparity/routing/Routes.scala`, remove this line (keep every `accounts/:id/...` sub-route — only the bare list route goes):

```scala
          path("accounts")(AccountsPage()),
```

- [ ] **Step 3: Remove the unused Nav helper**

In `frontend/src/main/scala/onparity/routing/Nav.scala`, remove:

```scala
  def toAccounts(ff: FundFlowPayload): Unit =
    AppState.activeFundFlow.set(Some(ff))
    push("/components/accounts")
```

And since `AccountPayload` was already unused in this file even before this change (confirmed via grep — only appeared in the import), drop it from the import line too:

```scala
import onparity.common.model.FundFlowPayload
```

(replacing `import onparity.common.model.{AccountPayload, FundFlowPayload}`)

- [ ] **Step 4: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors (confirms nothing else referenced `AccountsPage` or `Nav.toAccounts`).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/AccountsPage.scala frontend/src/main/scala/onparity/routing/Routes.scala frontend/src/main/scala/onparity/routing/Nav.scala
git commit -m "Delete orphaned AccountsPage (unreachable, redundant with FundFlowPage's Accounts tab)"
```

---

### Task 3: Create PageHeader and adopt it across list pages

**Files:**
- Create: `frontend/src/main/scala/onparity/view/components/PageHeader.scala`
- Modify: `frontend/src/main/scala/onparity/view/pages/EventsPage.scala:9,36`
- Modify: `frontend/src/main/scala/onparity/view/pages/JobsPage.scala:11,35`
- Modify: `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala:10,63`
- Modify: `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala:9,70`
- Modify: `frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala:9,47-53`
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowsPage.scala:10,30`
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala:9,105`

**Interfaces:**
- Produces: `PageHeader.title(mods: Modifier[HtmlElement]*): HtmlElement` — accepts either a plain `String` (via Laminar's built-in `String => Modifier[HtmlElement]` conversion) or reactive modifiers like `child.text <-- signal`, since `UnreconciledMovementsPage`'s title is dynamic, not a static string.

- [ ] **Step 1: Create PageHeader.scala**

```scala
package onparity.view.components

import com.raquo.laminar.api.L.*

object PageHeader:
  def title(mods: Modifier[HtmlElement]*): HtmlElement =
    h2((cls := "text-2xl font-medium mb-6") +: mods*)
```

- [ ] **Step 2: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 3: Adopt in EventsPage**

In `frontend/src/main/scala/onparity/view/pages/EventsPage.scala`:
- Line 9: change `import onparity.view.components.{Form, Icons, Table}` to `import onparity.view.components.{Form, Icons, PageHeader, Table}`
- Line 36: change `h2(cls := "text-2xl font-medium mb-6", "Events"),` to `PageHeader.title("Events"),`

- [ ] **Step 4: Adopt in JobsPage**

In `frontend/src/main/scala/onparity/view/pages/JobsPage.scala`:
- Line 11: change `import onparity.view.components.{Form, Icons, Table}` to `import onparity.view.components.{Form, Icons, PageHeader, Table}`
- Line 35: change `h2(cls := "text-2xl font-medium mb-6", "Jobs"),` to `PageHeader.title("Jobs"),`

- [ ] **Step 5: Adopt in ReconciliationsPage**

In `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala`:
- Line 10: change `import onparity.view.components.{Buttons, Table}` to `import onparity.view.components.{Buttons, PageHeader, Table}`
- Line 63: change `h2(cls := "text-2xl font-medium mb-6", "Reconciliations"),` to `PageHeader.title("Reconciliations"),`

- [ ] **Step 6: Adopt in ManualMatchPage**

In `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala`:
- Line 9: change `import onparity.view.components.{Buttons, Icons, Table}` to `import onparity.view.components.{Buttons, Icons, PageHeader, Table}`
- Line 70: change `h2(cls := "text-2xl font-medium mb-6", "Manual Match"),` to `PageHeader.title("Manual Match"),`

- [ ] **Step 7: Adopt in UnreconciledMovementsPage**

In `frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala`:
- Line 9: change `import onparity.view.components.{Buttons, Table}` to `import onparity.view.components.{Buttons, PageHeader, Table}`
- Lines 47-53, change:

```scala
        h2(
          cls := "text-2xl font-medium mb-6",
          child.text <-- state.signal.map { s =>
            val name = s.accounts.find(_.id.contains(accountId)).map(_.name).getOrElse(accountId)
            s"$name — Unreconciled Movements"
          }
        ),
```

to:

```scala
        PageHeader.title(
          child.text <-- state.signal.map { s =>
            val name = s.accounts.find(_.id.contains(accountId)).map(_.name).getOrElse(accountId)
            s"$name — Unreconciled Movements"
          }
        ),
```

- [ ] **Step 8: Adopt in FundFlowsPage (fixes the text-xl drift)**

In `frontend/src/main/scala/onparity/view/pages/FundFlowsPage.scala`:
- Line 10: change `import onparity.view.components.{Buttons, Table}` to `import onparity.view.components.{Buttons, PageHeader, Table}`
- Line 30: change `h2(cls := "text-xl font-medium", "Ledgers"),` to `PageHeader.title("Ledgers"),`

- [ ] **Step 9: Adopt in FundFlowPage (already correct — DRY, zero visual change)**

In `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala`:
- Line 9: change `import onparity.view.components.Table` to `import onparity.view.components.{PageHeader, Table}`
- Line 105: change `h2(cls := "text-2xl font-medium mb-6", ff.name)` to `PageHeader.title(ff.name)`

- [ ] **Step 10: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/main/scala/onparity/view/components/PageHeader.scala frontend/src/main/scala/onparity/view/pages/EventsPage.scala frontend/src/main/scala/onparity/view/pages/JobsPage.scala frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala frontend/src/main/scala/onparity/view/pages/FundFlowsPage.scala frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala
git commit -m "Extract PageHeader.title and adopt across list pages, fixing FundFlowsPage's text-xl drift"
```

---

### Task 4: Fix FundFlowPage's raw-color "New Account" button

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala:9,122-131`

**Interfaces:**
- Consumes: `Buttons.linkButton(label: String, obs: Observer[Unit]): HtmlElement` (existing, `view/components/Buttons.scala:6`)

- [ ] **Step 1: Add the Buttons import**

Change (from Task 3, Step 9) `import onparity.view.components.{PageHeader, Table}` to `import onparity.view.components.{Buttons, PageHeader, Table}`

- [ ] **Step 2: Replace the raw button**

Change:

```scala
              div(
                cls := "mb-4",
                button(
                  cls := "px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded hover:bg-blue-700",
                  typ := "button",
                  onClick --> { _ =>
                    state.update(_.copy(selectedAccount = None, selectedMovementSpec = None))
                    Nav.toMaintainAccount(None)
                  },
                  "New Account"
                )
              ),
```

to:

```scala
              div(
                cls := "mb-4",
                Buttons.linkButton(
                  "New Account",
                  Observer[Unit](_ =>
                    state.update(_.copy(selectedAccount = None, selectedMovementSpec = None))
                    Nav.toMaintainAccount(None)
                  )
                )
              ),
```

- [ ] **Step 3: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala
git commit -m "Fix FundFlowPage's raw-color New Account button to use the shared Buttons component"
```

---

### Task 5: Fix LoginPage — broken class, raw color, header/card consistency

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/LoginPage.scala:11,63-76`

**Interfaces:**
- Consumes: `Buttons.linkButton(label: String, obs: Observer[Unit]): HtmlElement` (existing, `view/components/Buttons.scala:6`)

- [ ] **Step 1: Add the Buttons import**

Change `import onparity.view.components.Icons` to `import onparity.view.components.{Buttons, Icons}`

- [ ] **Step 2: Fix the outer background, card, header, and button**

`bg-base-150` is not a defined Tailwind/token class anywhere in `tailwind.config.js` — it currently renders as an unstyled fallback. Change:

```scala
      cls := "h-screen flex justify-center bg-base-150",
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white border border-gray-200 rounded-lg shadow-sm sm:p-6 md:p-8",
        h5(cls := "text-xl font-medium text-gray-900 mb-4", "Login"),
        formField("Username", "text", Icons.user, v => state.update(_.copy(username = v))),
        formField("Password", "password", Icons.lock, v => state.update(_.copy(password = v))),
        child <-- state.signal.map(_.error.fold(div())(msg => p(cls := "text-red-500 mb-2", msg))),
        button(
          cls := "w-full text-white bg-purple-700 hover:bg-purple-800 font-medium rounded-lg text-sm px-5 py-2.5 text-center mt-4",
          typ := "button",
          onClick --> (_ => loginBus.emit(())),
          "Login"
        )
      )
```

to:

```scala
      cls := "h-screen flex justify-center bg-md-surface-variant dark:bg-gray-900",
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm sm:p-6 md:p-8",
        h2(cls := "text-xl font-medium text-md-on-surface mb-4", "Login"),
        formField("Username", "text", Icons.user, v => state.update(_.copy(username = v))),
        formField("Password", "password", Icons.lock, v => state.update(_.copy(password = v))),
        child <-- state.signal.map(_.error.fold(div())(msg => p(cls := "text-red-500 mb-2", msg))),
        div(cls := "mt-4 text-center", Buttons.linkButton("Login", Observer[Unit](_ => loginBus.emit(()))))
      )
```

Note: `formField`'s individual input/label styling (raw `gray-900`/`gray-50`/`gray-300`, no dark-mode classes) is left as-is — out of scope for this pass, which targets the container/header/button chrome found in the audit, not full dark-mode coverage of every form input in the app.

- [ ] **Step 3: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/LoginPage.scala
git commit -m "Fix LoginPage: broken bg-base-150 class, raw purple button, header/card token migration"
```

---

### Task 6: WelcomePage top-spacing consistency

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/WelcomePage.scala:24`

**Interfaces:**
- Consumes: nothing new
- Produces: nothing new

- [ ] **Step 1: Add pt-20**

Every other page uses `pt-20` so its content clears the fixed error banner (`MainLayout.scala:22-30`, `fixed top-0 ... py-3`, roughly 80px tall when shown) consistently; `WelcomePage` is the only one missing it. Change:

```scala
      cls := "flex flex-col items-center mb-8",
```

to:

```scala
      cls := "flex flex-col items-center mb-8 pt-20",
```

The distinct `h1`/hero-style title is left as-is — intentional for the landing page, not part of the drift found in the audit.

- [ ] **Step 2: Compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/WelcomePage.scala
git commit -m "Add consistent top spacing to WelcomePage"
```

---

### Task 7: Final verification pass

**Files:** none (verification only)

- [ ] **Step 1: Full compile check**

Run: `sbt "frontend/Compile/fastOptJS"`
Expected: compiles with no errors (final confirmation across all tasks together).

- [ ] **Step 2: Start the dev server**

Run: `sbt frontend/devServer` (or the `dev` alias)

- [ ] **Step 3: Manual browser walkthrough (user-performed — no browser tool available to verify this directly)**

Check each of these in a browser against the dev server:
- `/login` — card no longer has an undefined-class background; Login button is now a pill matching the rest of the app; dark mode toggle (if triggered) shows a dark card
- `/` (Welcome) — content no longer sits flush at the very top
- `/components/fund-flows` — "Ledgers" title now matches the size of every other list page's title
- `/components/jobs`, `/components/events`, `/components/fund-flow` → Accounts tab, `/components/accounts/:id/reconciliations`, `/components/accounts/:id/unreconciled-movements`, `/components/accounts/:id/manual-match` — titles render correctly (including the dynamic one on Unreconciled Movements)
- Left nav — only Home/Ledgers/Jobs/Events/Logout remain (no dead links), hover color is the app's purple accent instead of the old raw purple
- `/components/accounts` (typed directly) — should now 404/redirect via `noneMatched` to `/login`, since the route was removed
- `/components/fund-flow` → Configuration tab → "New Account" button is now the pill-shaped `Buttons.linkButton` style instead of the old rectangular blue button

- [ ] **Step 4: Report back**

Confirm to the user which of the Step 3 checks passed/failed based on their browser walkthrough, since I cannot verify this myself.
