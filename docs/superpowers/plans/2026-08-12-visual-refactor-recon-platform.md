# Visual Refactor — Reconciliation Platform Look — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the six reconciliation-focused pages (`FundFlowPage`, `ReconSpecsPage`,
`ReconciliationsPage`, `ManualMatchPage`, `UnreconciledMovementsPage`, plus the nav shell) up to
the violet/pill/card visual language from the approved mockup, fixing a real bug along the way
(`Table.scala` currently renders unstyled because it references undefined Tailwind classes).

**Architecture:** Pure frontend styling/component change in the existing Scala.js + Laminar +
Tailwind stack. No backend, API, or domain-model changes. Two shared components are
touched/added (`Table.scala` rewrite, new `Pill.scala`); five pages get targeted edits to use
them; the left nav is regrouped and gains five "coming soon" placeholder routes for
functionality that doesn't exist yet; a dark-mode toggle is added (there currently is none).

**Tech Stack:** Scala 3 / Scala.js, Laminar (reactive DOM), Tailwind CSS (JIT, `darkMode:
'selector'`), sbt (`frontend/compile` to type-check, `frontend/devServer` to run
`~fastOptJS` + the Vite dev server on `localhost:9876`).

## Global Constraints

- No backend/API changes. No new domain functionality, no fake/mock data anywhere.
- Design doc: `docs/superpowers/specs/2026-08-12-visual-refactor-recon-platform-design.md` —
  every token value and page decision below is copied from it verbatim.
- Color tokens (exact hex, light / dark):
  `md-primary` `#6d28d9` / `#a78bfa`; `md-primary-hover` `#5b21b6` / `#c4b0fb`;
  `md-on-primary` `#ffffff` / `#211a3d`; `md-primary-container` `#f1ecfd` / `#362a5c`;
  `md-on-primary-container` `#4c1d95` / `#ede9fe`; `md-surface-alt` `#f1eefa` / `#221e35`;
  `md-good` `#0ca30c` / `#3fcf5f`, `md-good-bg` `#e4f6e4` / `#163821`;
  `md-warning` `#a86400` / `#f0b13a`, `md-warning-bg` `#fdf0d2` / `#3c2f10`;
  `md-serious` `#c1502a` / `#f0916a`, `md-serious-bg` `#fbe6dc` / `#3d2415`;
  `md-critical` `#d03b3b` / `#ef6f6f`, `md-critical-bg` `#fbe2e2` / `#3a1717`.
- Dark-mode wiring follows the existing project pattern: `darkMode: 'selector'` in
  `tailwind.config.js`, scoped by the `dark` class already applied by `MainLayout` — every new
  dark value is a separate `-dark`-suffixed token consumed via Tailwind's `dark:` variant
  (e.g. `bg-md-primary dark:bg-md-primary-dark`), matching how `LeftNav`/`LoginPage` already do
  it (see `docs/superpowers/specs/2026-08-11-frontend-nav-consistency-design.md`).
- No `Card` component — `Table.scala`'s own rewrite already provides the bordered/shadowed
  surface; do not add a second wrapper around it.
- Verify every task with `sbt frontend/compile` (must succeed with no errors) **and** a real
  browser check via `sbt frontend/devServer` (starts `~fastOptJS` + Vite on
  `http://localhost:9876`) — the backend must already be running (`sbt backend/run`) and the
  local DB reachable, since these pages fetch real data. Log in with your local dev credentials.

---

### Task 1: Design tokens + fix `Table.scala`

**Files:**
- Modify: `frontend/tailwind.config.js`
- Modify: `frontend/src/main/resources/index.css`
- Modify: `frontend/src/main/scala/onparity/view/components/Table.scala`

**Interfaces:**
- Produces: `Table.create(headers: Seq[String], rows: Seq[Seq[HtmlElement]], numCols: Set[Int] = Set.empty): HtmlElement` — the `numCols` param is new and defaults to empty, so every existing call site keeps compiling unchanged.
- Produces: CSS class `.num` (tabular-nums), and the tailwind color tokens listed in Global Constraints, both used by later tasks.

- [ ] **Step 1: Replace `frontend/tailwind.config.js` color tokens**

```js
module.exports = {
  content: [
    './src/main/scala/**/*.scala',
    "./node_modules/flowbite/**/*.js"
  ],
  theme: {
    extend: {
      colors: {
        'md-primary':                   '#6d28d9',
        'md-primary-dark':              '#a78bfa',
        'md-primary-hover':             '#5b21b6',
        'md-primary-hover-dark':        '#c4b0fb',
        'md-on-primary':                '#ffffff',
        'md-on-primary-dark':           '#211a3d',
        'md-primary-container':         '#f1ecfd',
        'md-primary-container-dark':    '#362a5c',
        'md-on-primary-container':      '#4c1d95',
        'md-on-primary-container-dark': '#ede9fe',
        'md-surface-variant':           '#ece6f0',
        'md-surface-alt':               '#f1eefa',
        'md-surface-alt-dark':          '#221e35',
        'md-on-surface':                '#1c1b1f',
        'md-on-surface-variant':        '#49454f',
        'md-outline':                   '#79747e',
        'md-error':                     '#b3261e',
        'md-good':                      '#0ca30c',
        'md-good-dark':                 '#3fcf5f',
        'md-good-bg':                   '#e4f6e4',
        'md-good-bg-dark':              '#163821',
        'md-warning':                   '#a86400',
        'md-warning-dark':              '#f0b13a',
        'md-warning-bg':                '#fdf0d2',
        'md-warning-bg-dark':           '#3c2f10',
        'md-serious':                   '#c1502a',
        'md-serious-dark':              '#f0916a',
        'md-serious-bg':                '#fbe6dc',
        'md-serious-bg-dark':           '#3d2415',
        'md-critical':                  '#d03b3b',
        'md-critical-dark':             '#ef6f6f',
        'md-critical-bg':               '#fbe2e2',
        'md-critical-bg-dark':          '#3a1717',
      }
    },
  },
  darkMode: 'selector',
  plugins: [
    require('flowbite/plugin')
  ],
}
```

This replaces the old `md-primary` (`#6750a4`), `md-primary-hover` (`#5b4397`), and
`md-primary-container` (`#eaddff`) values with the new violet ones, and adds the `-dark`
variants plus the four status-color pairs. Every existing usage of `md-primary` /
`md-primary-hover` / `md-primary-container` / `md-on-primary` / `md-surface-variant` /
`md-on-surface` / `md-on-surface-variant` / `md-outline` / `md-error` keeps working unchanged
(same token names, new hex for the ones that changed).

- [ ] **Step 2: Add the `.num` utility to `frontend/src/main/resources/index.css`**

```css
@import url("https://fonts.googleapis.com/css?family=IBM+Plex+Sans");
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@100..900&display=swap');

@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
    html {
        font-family: "Inter";
        font-weight: 400;
    }
}

@layer utilities {
    .num {
        font-variant-numeric: tabular-nums;
    }
}
```

- [ ] **Step 3: Rewrite `frontend/src/main/scala/onparity/view/components/Table.scala`**

```scala
package onparity.view.components

import com.raquo.laminar.api.L.*

object Table:
  def create(headers: Seq[String], rows: Seq[Seq[HtmlElement]], numCols: Set[Int] = Set.empty): HtmlElement =
    def cellCls(i: Int, base: String): String =
      if numCols.contains(i) then s"$base text-right num" else base

    div(
      cls := "relative overflow-x-auto bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-md-outline dark:border-gray-700",
      table(
        cls := "w-full text-sm text-left rtl:text-right text-md-on-surface dark:text-gray-200",
        thead(
          cls := "text-xs uppercase text-md-on-surface-variant dark:text-gray-400 bg-md-surface-alt dark:bg-md-surface-alt-dark border-b border-md-outline dark:border-gray-700",
          tr(
            headers.zipWithIndex.map((h, i) => th(cls := cellCls(i, "px-6 py-3 font-medium"), h))*
          )
        ),
        tbody(
          rows.map(row =>
            tr(cls := "bg-white dark:bg-gray-800 border-b border-md-outline dark:border-gray-700 last:border-b-0").amend(
              row.zipWithIndex.map((col, i) => td(cls := cellCls(i, "px-6 py-4 align-top"), col))*
            )
          )*
        )
      )
    )
```

This drops the undefined `bg-neutral-primary-soft` / `text-body` / `border-default` /
`bg-neutral-secondary-soft` classes (they generated no CSS, so every table in the app currently
renders with no border/background/header styling at all) and replaces them with real tokens,
plus the new optional `numCols` param for right-aligned tabular-nums columns.

- [ ] **Step 4: Type-check**

Run: `sbt frontend/compile`
Expected: `[success] Total time: ...` with no errors.

- [ ] **Step 5: Visual check — confirm the table bug is fixed**

Run: `sbt frontend/devServer` (leave running), open `http://localhost:9876`, log in, and
navigate to **Events** (left nav). Before this change the events table rendered as plain
unstyled browser HTML (no border, no shaded header, cramped padding); after this change it
should show a bordered, rounded, shadowed card with a light-purple-tinted header row
(`md-surface-alt`) and comfortable cell padding — with no code changes to `EventsPage.scala`
itself, since the fix lives entirely in the shared `Table` component. Stop the dev server when
done (`Ctrl-C`).

- [ ] **Step 6: Commit**

```bash
git add frontend/tailwind.config.js frontend/src/main/resources/index.css frontend/src/main/scala/onparity/view/components/Table.scala
git commit -m "Fix unstyled Table component and adopt new violet/status design tokens

Table.scala referenced Tailwind classes (bg-neutral-primary-soft, text-body,
border-default) that were never defined, so every table in the app rendered
with no styling. Replaces them with real tokens and adds an optional numCols
param for tabular-nums alignment, used by later reconciliation-page work."
```

---

### Task 2: `Pill.scala` + `ReconciliationsPage` status pills

**Files:**
- Create: `frontend/src/main/scala/onparity/view/components/Pill.scala`
- Modify: `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala`

**Interfaces:**
- Consumes: `Table.create(..., numCols: Set[Int])` from Task 1.
- Produces: `Pill.apply(text: String, tone: Pill.Tone): HtmlElement` and `Pill.Tone` (enum:
  `Good, Warning, Serious, Critical, Neutral, Accent`) — used by Tasks 3 and 5.

- [ ] **Step 1: Create `Pill.scala`**

```scala
package onparity.view.components

import com.raquo.laminar.api.L.*

object Pill:
  enum Tone:
    case Good, Warning, Serious, Critical, Neutral, Accent

  def apply(text: String, tone: Tone): HtmlElement =
    val toneCls = tone match
      case Tone.Good     => "bg-md-good-bg dark:bg-md-good-bg-dark text-md-good dark:text-md-good-dark"
      case Tone.Warning  => "bg-md-warning-bg dark:bg-md-warning-bg-dark text-md-warning dark:text-md-warning-dark"
      case Tone.Serious  => "bg-md-serious-bg dark:bg-md-serious-bg-dark text-md-serious dark:text-md-serious-dark"
      case Tone.Critical => "bg-md-critical-bg dark:bg-md-critical-bg-dark text-md-critical dark:text-md-critical-dark"
      case Tone.Neutral  => "bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300"
      case Tone.Accent   => "bg-md-primary-container dark:bg-md-primary-container-dark text-md-on-primary-container dark:text-md-on-primary-container-dark"
    span(cls := s"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold $toneCls", text)
```

- [ ] **Step 2: Type-check the new file in isolation**

Run: `sbt frontend/compile`
Expected: success (nothing calls `Pill` yet, so this only confirms `Pill.scala` itself is
valid Scala).

- [ ] **Step 3: Wire `Pill` into `ReconciliationsPage.scala`**

Add `Pill` to the import on line 10:

```scala
import onparity.view.components.{Buttons, PageHeader, Pill, Table}
```

Add a private helper above `apply` (after the `case class State` block, before `def apply`):

```scala
  private def statusPill(r: ReconciliationPayload): HtmlElement =
    val (label, tone) = r.status match
      case "InProgress"                                      => "In Progress" -> Pill.Tone.Warning
      case "Final" if r.adjustmentReason.exists(_.nonEmpty)   => "Written Off" -> Pill.Tone.Serious
      case "Final"                                            => "Final"      -> Pill.Tone.Good
      case "Voided"                                           => "Voided"     -> Pill.Tone.Neutral
      case other                                              => other        -> Pill.Tone.Neutral
    Pill(label, tone)
```

This derives the mockup's four-way status distinction from data already on
`ReconciliationPayload` — the backend has no separate "written off" status
(`ManualReconciler.writeOff` sets `status = Final` with `adjustmentReason` populated), so a
`Final` row with a non-empty `adjustmentReason` is relabeled "Written Off" without any new
field.

Replace the `Table.create` call (the `span(r.status),` line and the closing paren) so the
status cell uses the pill and the table knows column 3 (`Balance`) is numeric:

```scala
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Balance", "Adjustment Reason", "Created At", "Updated At", "Actions"),
            s.recons.map(r =>
              Seq(
                span(r.description),
                statusPill(r),
                span(r.appliedRules),
                span(r.balance.toString),
                span(r.adjustmentReason.getOrElse("")),
                span(ViewHelper.formatDateTime(r.createdAt)),
                span(ViewHelper.formatDateTime(r.updatedAt)),
                div(
                  cls := (if r.status == "Voided" then "opacity-60" else ""),
                  if r.status == "Voided" then span("—"): HtmlElement
                  else
                    div(
                      cls := "flex flex-col gap-1",
                      (if r.status == "InProgress" then
                         if s.editingWriteOff.contains(r.id) then
                           div(
                             cls := "flex gap-1",
                             input(
                               typ         := "text",
                               placeholder := "reason",
                               onInput.mapToValue --> Observer[String](v =>
                                 state.update(st => st.copy(writeOffReasonBy = st.writeOffReasonBy + (r.id -> v)))
                               )
                             ),
                             Buttons.textButton("Confirm", Observer[Unit](_ => writeOffBus.emit(r.id)))
                           )
                         else
                           Buttons.textButton("Write Off", Observer[Unit](_ => state.update(_.copy(editingWriteOff = Some(r.id)))))
                       else
                         span(): HtmlElement),
                      Buttons.textButton("Unmatch", Observer[Unit](_ => unmatchBus.emit(r.id)))
                    )
                )
              )
            ),
            numCols = Set(3)
          )
        }
```

(Only the `statusPill(r)` line and the added `numCols = Set(3)` parameter are new; the actions
column logic is unchanged from today.)

- [ ] **Step 4: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 5: Visual check**

Run: `sbt frontend/devServer`, log in, navigate to a fund flow → an account with a recon
spec → **Reconciliations**. Confirm: `Status` column shows colored pills (amber "In Progress",
green "Final", gray "Voided", and orange "Written Off" if any written-off records exist in
your dev data), and the `Balance` column is right-aligned with tabular-nums digits. Stop the
dev server.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/scala/onparity/view/components/Pill.scala frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala
git commit -m "Add Pill component and use it for reconciliation status

Derives a four-way status label (In Progress / Final / Written Off / Voided)
from ReconciliationPayload's existing status + adjustmentReason fields."
```

---

### Task 3: `ReconSpecsPage` relationship pill

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ReconSpecsPage.scala`

**Interfaces:**
- Consumes: `Pill.apply`/`Pill.Tone` from Task 2, `Table.create(..., numCols)` from Task 1.

- [ ] **Step 1: Update the import**

```scala
import onparity.view.components.{Buttons, Pill, Table}
```

- [ ] **Step 2: Replace the relationship cell and add `numCols`**

```scala
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Aging Limit", "Relationship", ""),
            s.reconSpecs.map { spec =>
              Seq(
                span(spec.agingLimit.toString),
                Pill(spec.relationship.toString, Pill.Tone.Accent),
                div(
                  cls := "flex gap-2",
                  Buttons.textButton(
                    "Ingest Files",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toIngest(accountId)
                    )
                  ),
                  Buttons.textButton(
                    "Edit",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toMaintainReconSpec(accountId)
                    )
                  ),
                  Buttons.textButton(
                    "Reconciliations",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toReconciliations(accountId)
                    )
                  )
                )
              )
            },
            numCols = Set(0)
          )
        }
```

- [ ] **Step 3: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 4: Visual check**

Run: `sbt frontend/devServer`, log in, navigate to a fund flow → an account → **Recon
Specs** (via the "Configure Reconciliation" menu item on an account node, or however you
currently reach `ReconSpecsPage` in your dev flow). Confirm `Relationship` (e.g. `OneToOne`)
renders as a violet pill and `Aging Limit` is right-aligned. Stop the dev server.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ReconSpecsPage.scala
git commit -m "Use Pill for recon spec relationship and align aging limit numerically"
```

---

### Task 4: `ManualMatchPage` + `UnreconciledMovementsPage` numeric alignment

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala`
- Modify: `frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala`

**Interfaces:**
- Consumes: `Table.create(..., numCols)` from Task 1. No `Pill` usage in this task.

- [ ] **Step 1: Add `numCols` to both tables in `ManualMatchPage.scala`**

The In Movements table (headers `Seq("", "Reference ID", "Amount")`, `Amount` at index 2):

```scala
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.inMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                ),
                numCols = Set(2)
              )
            }
```

The Out Movements table (same header shape, same fix):

```scala
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.outMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                ),
                numCols = Set(2)
              )
            }
```

- [ ] **Step 2: Add `numCols` to `UnreconciledMovementsPage.scala`**

Headers are `Seq("Reference ID", "Amount", "In Account", "Out Account")`, `Amount` at index 1:

```scala
        child <-- state.signal.map { s =>
          val nameById = s.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
          Table.create(
            Seq("Reference ID", "Amount", "In Account", "Out Account"),
            s.movements.map(m =>
              Seq(
                span(m.referenceId),
                span(m.amount.toString),
                span(nameById.getOrElse(m.inAccountId, m.inAccountId)),
                span(nameById.getOrElse(m.outAccountId, m.outAccountId))
              )
            ),
            numCols = Set(1)
          )
        }
```

- [ ] **Step 3: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 4: Visual check**

Run: `sbt frontend/devServer`, log in. Navigate to a fund flow → an account with unreconciled
movements → click the unreconciled count to reach **Unreconciled Movements**; confirm `Amount`
is right-aligned tabular-nums. Then click **Manual Match**; confirm both the In and Out
`Amount` columns are right-aligned tabular-nums. Stop the dev server.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala
git commit -m "Right-align Amount columns with tabular-nums in Manual Match and Unreconciled Movements"
```

---

### Task 5: `FundFlowPage` — account type pill, numeric columns, diagram colors

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala`
- Modify: `frontend/src/main/resources/index.css`

**Interfaces:**
- Consumes: `Pill.apply`/`Pill.Tone` from Task 2, `Table.create(..., numCols)` from Task 1.
- Produces: CSS custom properties `--md-primary` / `--md-primary-container` (light and, under
  `.dark`, dark values) for the hand-built SVG diagram, which draws with raw `fill=`/`stroke=`
  attributes and can't use Tailwind classes directly.

- [ ] **Step 1: Add CSS custom properties to `frontend/src/main/resources/index.css`**

```css
@import url("https://fonts.googleapis.com/css?family=IBM+Plex+Sans");
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@100..900&display=swap');

@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
    html {
        font-family: "Inter";
        font-weight: 400;
    }

    :root {
        --md-primary: #6d28d9;
        --md-primary-container: #f1ecfd;
    }

    .dark {
        --md-primary: #a78bfa;
        --md-primary-container: #362a5c;
    }
}

@layer utilities {
    .num {
        font-variant-numeric: tabular-nums;
    }
}
```

(`.dark` is the same class `MainLayout` already applies to the app root for Tailwind's
`darkMode: 'selector'`, so this cascades to the SVG the same way the rest of dark mode works.)

- [ ] **Step 2: Update `buildSvg` in `FundFlowPage.scala` to use the CSS variables**

Replace the account-node `fill`/`stroke` lines inside `buildSvg`:

```scala
    accounts.foreach { acc =>
      acc.id.foreach { id =>
        posOf.get(id).foreach { (x, y) =>
          val fill   = if acc.isRecon then "var(--md-primary-container)" else "white"
          val stroke = if acc.isRecon then "var(--md-primary)" else "#d1d5db"
          sb.append(
            s"""<rect x="$x" y="$y" width="$boxW" height="$boxH" rx="8" fill="$fill" stroke="$stroke" stroke-width="2" />"""
          )
          sb.append(
            s"""<text x="${x + boxW / 2}" y="${y + boxH / 2}" text-anchor="middle" dominant-baseline="middle" fill="#374151" font-size="13" font-family="sans-serif">${xmlEscape(
                acc.name
              )}</text>"""
          )
        }
      }
    }
```

(Only the `fill`/`stroke` variable assignments change — was `"#f3f0ff"`/`"#7c3aed"`, now the
CSS var references.)

- [ ] **Step 3: Update the import**

```scala
import onparity.view.components.{Buttons, Pill, PageHeader, Table}
```

- [ ] **Step 4: Replace the Account Type cell and add `numCols` in the "Account" tab table**

```scala
          case "Account" =>
            div(
              child <-- state.signal.map { s =>
                Table.create(
                  Seq(
                    "Name",
                    "Account Type",
                    "Cash Balance",
                    "Reconciled Balance",
                    "Unreconciled Balance",
                    "Unreconciled Count",
                    "",
                    "",
                    "",
                    ""
                  ),
                  s.accounts.sortBy(_.name).map { account =>
                    val accountId    = account.id.getOrElse("")
                    val status       = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
                    val unreconCount = s.unreconciledCounts.getOrElse(accountId, 0)
                    val unreconCell =
                      if account.isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick --> (_ => Nav.toUnreconciledMovements(accountId)),
                          unreconCount.toString
                        )
                      else span(cls := "text-right w-full inline-block", unreconCount.toString)
                    Seq(
                      span(cls := "inline-block w-40", account.name),
                      if account.isRecon then Pill("Recon", Pill.Tone.Accent) else Pill("Source", Pill.Tone.Neutral),
                      span(cls := "text-right w-full inline-block", f"$$${account.balance}%.2f"),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.reconciledBalance}%.2f" else ""
                      ),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.unreconciledBalance}%.2f" else ""
                      ),
                      unreconCell
                    )
                  },
                  numCols = Set(2, 3, 4, 5)
                )
              }
            )
```

(Only the `Pill(...)` line replacing the plain `span(if account.isRecon then "Recon" else
"Source")`, and the added `numCols = Set(2, 3, 4, 5)`, are new — everything else in this block
is unchanged from today.)

- [ ] **Step 5: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 6: Visual check**

Run: `sbt frontend/devServer`, log in, open a fund flow. On the **Accounts** tab, confirm the
"Account Type" column shows violet "Recon" / gray "Source" pills and the four balance/count
columns are right-aligned. Switch to the **Configuration** tab and confirm reconciling-account
boxes in the diagram still show the light-violet fill with violet border (same look as before,
now token-driven) and non-reconciling accounts are still plain white with gray border. Toggle
dark mode once Task 7 is done to confirm the diagram box colors switch too (safe to skip this
part of the check until Task 7 lands, since there's no dark-mode toggle yet). Stop the dev
server.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala frontend/src/main/resources/index.css
git commit -m "Use Pill for account type and CSS-var tokens for the fund flow diagram

The hand-built SVG diagram draws with raw fill/stroke attributes instead of
Tailwind classes, so its colors now come from --md-primary/--md-primary-container
CSS custom properties (scoped under the same .dark class Tailwind's darkMode:
'selector' already uses) instead of hardcoded hex."
```

---

### Task 6: Regroup `LeftNav`, add "coming soon" placeholder pages

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/components/Icons.scala`
- Modify: `frontend/src/main/scala/onparity/view/layout/LeftNav.scala`
- Create: `frontend/src/main/scala/onparity/view/pages/ComingSoonPage.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala`

**Interfaces:**
- Consumes: `PageHeader.title(mods: Modifier[HtmlElement]*): HtmlElement` (existing).
- Produces: `ComingSoonPage.apply(title: String): HtmlElement`; 5 new routes under
  `/components/coming-soon/*`.

- [ ] **Step 1: Fix a reuse bug in `Icons.scala` before regrouping the nav**

`Icons.document`, `Icons.chart`, and `Icons.settings` are currently `val`s — singleton DOM
nodes. The current `LeftNav` already reuses `Icons.document` three times in the same render
(Ledgers, Jobs, Events all pass `Icons.document`), which is broken today: inserting the same
DOM node into a second location *moves* it rather than duplicating it, so only the
last-mounted nav item actually shows an icon. The regrouped nav below reuses `document` (2×),
`chart` (2×), and `settings` (2×), so this needs fixing first. Change these three from `val` to
`def` — matching the pattern the file already documents for `plusCircle`/`minusCircle` ("used
once per row in a dynamic list ... each call must return a fresh node"):

```scala
  def document: HtmlElement = icon(
    """<svg class="w-5 h-5 text-gray-500 dark:text-white" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" width="24" height="24" fill="currentColor" viewBox="0 0 24 24"><path fill-rule="evenodd" d="M9 2.221V7H4.221a2 2 0 0 1 .365-.5L8.5 2.586A2 2 0 0 1 9 2.22ZM11 2v5a2 2 0 0 1-2 2H4v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2h-7Z" clip-rule="evenodd"/></svg>"""
  )
```

```scala
  def dashboard: HtmlElement = icon(
    """<svg class="w-5 h-5 text-gray-500 dark:text-white" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" width="24" height="24" fill="currentColor" viewBox="0 0 24 24"><path fill-rule="evenodd" d="M4 4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H4Zm10 5a1 1 0 0 1 1-1h3a1 1 0 1 1 0 2h-3a1 1 0 0 1-1-1Zm0 3a1 1 0 0 1 1-1h3a1 1 0 1 1 0 2h-3a1 1 0 0 1-1-1Zm0 3a1 1 0 0 1 1-1h3a1 1 0 1 1 0 2h-3a1 1 0 0 1-1-1Zm-8-5a3 3 0 1 1 6 0 3 3 0 0 1-6 0Zm1.942 4a3 3 0 0 0-2.847 2.051l-.044.133-.004.012c-.042.126-.055.167-.042.195.006.013.02.023.038.039.032.025.08.064.146.155A1 1 0 0 0 6 17h6a1 1 0 0 0 .811-.415.713.713 0 0 1 .146-.155c.019-.016.032-.026.038-.04.014-.027 0-.068-.042-.194l-.004-.012-.044-.133A3 3 0 0 0 10.059 14H7.942Z" clip-rule="evenodd"/></svg>"""
  )
```

(`dashboard` isn't used anywhere yet, but leaving it as a `val` while its neighbors become
`def` is an inconsistency trap for the next person who reuses it — convert it too, at no
cost.)

```scala
  def settings: HtmlElement = icon(
    """<svg class="w-5 h-5 text-gray-500 dark:text-white" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" width="24" height="24" fill="currentColor" viewBox="0 0 24 24"><path fill-rule="evenodd" d="M9.586 2.586A2 2 0 0 1 11 2h2a2 2 0 0 1 2 2v.089l.473.196.063-.063a2.002 2.002 0 0 1 2.828 0l1.414 1.414a2 2 0 0 1 0 2.827l-.063.064.196.473H20a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-.089l-.196.473.063.063a2.002 2.002 0 0 1 0 2.828l-1.414 1.414a2 2 0 0 1-2.828 0l-.063-.063-.473.196V20a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-.089l-.473-.196-.063.063a2.002 2.002 0 0 1-2.828 0l-1.414-1.414a2 2 0 0 1 0-2.827l.063-.064L4.089 15H4a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2h.09l.195-.473-.063-.063a2 2 0 0 1 0-2.828l1.414-1.414a2 2 0 0 1 2.827 0l.064.063L9 4.089V4a2 2 0 0 1 .586-1.414ZM8 12a4 4 0 1 1 8 0 4 4 0 0 1-8 0Z" clip-rule="evenodd"/></svg>"""
  )
```

```scala
  def chart: HtmlElement = icon(
    """<svg class="w-5 h-5 text-gray-500 dark:text-white" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" width="24" height="24" fill="currentColor" viewBox="0 0 24 24"><path fill-rule="evenodd" d="M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-6.616l-2.88 2.592C8.537 20.461 7 19.776 7 18.477V17H5a2 2 0 0 1-2-2V6Zm4 2a1 1 0 0 0 0 2h5a1 1 0 1 0 0-2H7Zm8 0a1 1 0 1 0 0 2h2a1 1 0 1 0 0-2h-2Zm-8 3a1 1 0 1 0 0 2h2a1 1 0 1 0 0-2H7Zm5 0a1 1 0 1 0 0 2h5a1 1 0 1 0 0-2h-5Z" clip-rule="evenodd"/></svg>"""
  )
```

(Replace exactly these four `val` declarations — `document`, `dashboard`, `settings`, `chart`
— with `def`. `home`, `user`, `logout`, `sun`, `moon`, `refresh`, `lock`, `github` stay as
`val` since nothing reuses them more than once within a single render tree.)

- [ ] **Step 2: Rewrite `LeftNav.scala`**

```scala
package onparity.view.layout

import com.raquo.laminar.api.L.*
import onparity.routing.Nav
import onparity.state.SessionStore
import onparity.view.components.Icons

object LeftNav:

  private case class NavItem(label: String, href: String, icon: HtmlElement)
  private case class NavGroup(label: String, items: Seq[NavItem])

  private def groups: Seq[NavGroup] = Seq(
    NavGroup("Overview", Seq(
      NavItem("Home", "/", Icons.home)
    )),
    NavGroup("Reconcile", Seq(
      NavItem("Ledgers", "/components/fund-flows", Icons.document),
      NavItem("Exceptions & Disputes", "/components/coming-soon/exceptions", Icons.document)
    )),
    NavGroup("Configure", Seq(
      NavItem("Recon Rules", "/components/coming-soon/recon-rules", Icons.settings),
      NavItem("Jobs", "/components/jobs", Icons.chart)
    )),
    NavGroup("Insights", Seq(
      NavItem("Reports & Analytics", "/components/coming-soon/reports", Icons.chart),
      NavItem("Events", "/components/events", Icons.document)
    )),
    NavGroup("Admin", Seq(
      NavItem("Users & Roles", "/components/coming-soon/users", Icons.user),
      NavItem("Settings", "/components/coming-soon/settings", Icons.settings)
    ))
  )

  def apply(): HtmlElement =
    asideTag(
      cls := "fixed left-0 top-0 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto",
      navTag(
        cls := "p-4",
        ul(
          cls := "space-y-1",
          groups.flatMap(navGroupItems)*
        ),
        div(
          cls := "mt-4 pt-4 border-t border-gray-300 dark:border-gray-600",
          button(
            cls := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors duration-200 w-full text-left",
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

  private def navGroupItems(g: NavGroup): Seq[HtmlElement] =
    li(
      cls := "px-4 pt-4 pb-1 text-xs font-semibold uppercase tracking-wide text-gray-400 dark:text-gray-500 first:pt-0",
      g.label
    ) +: g.items.map(navItem)

  private def navItem(item: NavItem): HtmlElement =
    li(
      a(
        href := item.href,
        cls  := "flex items-center space-x-3 px-4 py-2.5 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors duration-200",
        span(cls := "w-5 h-5", item.icon),
        span(item.label)
      )
    )
```

- [ ] **Step 3: Create `ComingSoonPage.scala`**

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.view.components.PageHeader

object ComingSoonPage:
  def apply(title: String): HtmlElement =
    div(
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        PageHeader.title(title),
        p(cls := "text-md-on-surface-variant dark:text-gray-400 text-sm", "This area isn't built yet.")
      )
    )
```

- [ ] **Step 4: Add the five placeholder routes in `Routes.scala`**

Add these five lines inside the `firstMatch(...)` block in `Routes.scala`, anywhere among the
other `path(...)` entries under `pathPrefix("components")`:

```scala
          path("coming-soon" / "exceptions")(ComingSoonPage("Exceptions & Disputes")),
          path("coming-soon" / "recon-rules")(ComingSoonPage("Recon Rules")),
          path("coming-soon" / "reports")(ComingSoonPage("Reports & Analytics")),
          path("coming-soon" / "users")(ComingSoonPage("Users & Roles")),
          path("coming-soon" / "settings")(ComingSoonPage("Settings")),
```

`Routes.scala` already does `import onparity.view.pages.*`, so `ComingSoonPage` needs no
new import there.

- [ ] **Step 5: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 6: Visual check**

Run: `sbt frontend/devServer`, log in. Confirm the left nav now shows five labeled groups
(Overview, Reconcile, Configure, Insights, Admin) each with a heading and 1–2 items, and that
**Ledgers**, **Jobs**, and **Events** all show a document/chart icon correctly (this is the
regression check for the `val`→`def` icon fix — before this task, at most one of Ledgers/Jobs/
Events showed an icon at a time). Click **Exceptions & Disputes**, **Recon Rules**, **Reports &
Analytics**, **Users & Roles**, and **Settings** — each should show a page with just the title
and "This area isn't built yet." Stop the dev server.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/main/scala/onparity/view/components/Icons.scala frontend/src/main/scala/onparity/view/layout/LeftNav.scala frontend/src/main/scala/onparity/view/pages/ComingSoonPage.scala frontend/src/main/scala/onparity/routing/Routes.scala
git commit -m "Regroup left nav into sections, add coming-soon placeholders

Also fixes a pre-existing icon-reuse bug: Icons.document/chart/settings were
vals (singleton DOM nodes), so reusing one for multiple nav items silently
moved the node instead of duplicating it, leaving earlier items iconless.
Converted the reused icons to defs, matching the pattern Icons.scala already
established for plusCircle/minusCircle."
```

---

### Task 7: Dark-mode toggle

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/layout/MainLayout.scala`

**Interfaces:**
- Consumes: `AppState.darkMode: Var[Boolean]` (existing, currently never set by any UI);
  `Icons.sun`/`Icons.moon` (existing, currently unused).

- [ ] **Step 1: Rewrite `MainLayout.scala`**

```scala
package onparity.view.layout

import com.raquo.laminar.api.L.*
import onparity.state.AppState
import onparity.view.components.Icons

object MainLayout:

  def apply(content: HtmlElement): HtmlElement =
    div(
      cls <-- AppState.darkMode.signal.map(d =>
        if d then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"
      ),
      LeftNav(),
      topBar(),
      mainTag(cls := "flex flex-col min-h-screen ml-64 bg-md-surface-variant dark:bg-gray-900", content)
    )

  private def topBar(): HtmlElement =
    div(
      cls := "fixed top-0 left-64 right-0 z-10 flex items-center justify-between px-6 py-3 bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700",
      child <-- AppState.globalError.signal.map {
        case None      => div()
        case Some(msg) => errorMessage(msg)
      },
      button(
        cls := "flex items-center justify-center w-9 h-9 rounded-full text-gray-600 dark:text-gray-300 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors",
        typ := "button",
        onClick --> (_ => AppState.darkMode.update(!_)),
        child <-- AppState.darkMode.signal.map(d => if d then Icons.sun else Icons.moon)
      )
    )

  private def errorMessage(message: String): HtmlElement =
    div(
      cls := "flex items-center gap-2 px-3 py-1 rounded bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-100 text-sm",
      span(cls := "font-medium", "Error:"),
      span(message),
      button(
        cls := "ml-2 text-red-600 dark:text-red-200 hover:text-red-900 dark:hover:text-red-50 font-bold text-lg leading-none",
        onClick --> (_ => AppState.globalError.set(None)),
        "×"
      )
    )
```

This replaces the old conditional-only error banner (`case None => div()`, no bar at all when
there's no error) with a bar that always renders — occupying the same fixed top strip every
page already reserves space for via `pt-20` — showing the error message when present and the
new dark-mode toggle on the right at all times. `AppState.darkMode.update(!_)` is `Var`'s
built-in toggle (flips the current `Boolean`); no new state-management code needed.

- [ ] **Step 2: Type-check**

Run: `sbt frontend/compile`
Expected: success.

- [ ] **Step 3: Visual check**

Run: `sbt frontend/devServer`, log in. Confirm a slim bar now sits at the top of the content
area (left edge starting after the sidebar) with a moon icon button on the right. Click it:
the page background, sidebar, and topbar should switch to dark styling and the icon should
change to a sun. Click again to switch back. Trigger a real error (e.g. temporarily stop the
backend and reload, or navigate somewhere that errors) to confirm the error message still
displays correctly alongside the toggle, then restart the backend. Stop the dev server.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/view/layout/MainLayout.scala
git commit -m "Add dark-mode toggle to the topbar

AppState.darkMode existed but nothing in the UI ever set it, and
Icons.sun/moon were defined but unused. Folds the toggle into the existing
fixed top strip (previously error-banner-only) so page layouts (which already
reserve space for it via pt-20) don't need to change."
```

---

## Self-Review

**Spec coverage:** Design tokens (Task 1) · Table fix (Task 1) · Pill component (Task 2) · no
Card component (confirmed — never introduced) · LeftNav regroup + coming-soon placeholders
(Task 6) · dark-mode topbar, no search box (Task 7) · FundFlowPage Pill + numCols + diagram
tokens (Task 5) · ReconSpecsPage Pill + numCols (Task 3) · ReconciliationsPage status Pill +
numCols, no drawer (Task 2) · ManualMatchPage numCols, no suggested-matches panel (Task 4) ·
UnreconciledMovementsPage numCols, no aging column (Task 4). Every spec line item maps to a
task; nothing in "Out of Scope" is touched by any task.

**Placeholder scan:** No TBD/TODO markers; every code block is complete, copy-pasteable
Scala/CSS/JS with real values, not descriptions of what to write.

**Type consistency:** `Pill.apply(text: String, tone: Pill.Tone): HtmlElement` and `Pill.Tone`
cases (`Good, Warning, Serious, Critical, Neutral, Accent`) are identical everywhere they're
referenced (Tasks 2, 3, 5). `Table.create`'s `numCols: Set[Int] = Set.empty` signature is
identical in Task 1's definition and every call site in Tasks 2–5. `ComingSoonPage.apply(title:
String): HtmlElement` matches its one call shape used five times in Task 6's `Routes.scala`
edit.
