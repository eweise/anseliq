# Visual Refactor — Reconciliation Platform Look

**Date:** 2026-08-12

## Goal

Bring the existing reconciliation-focused pages up to the visual language explored in the
"full vision" mockup (violet design system, status pills, card/table treatment, grouped
navigation) without adding functionality the backend doesn't support. This is a styling and
component-library pass, not a feature build.

## Background

A separate mockup (interactive HTML artifact, not committed to this repo) explored what a
complete commercial-grade reconciliation platform UI could look like — dashboard, exceptions
queue, approval workflow, reports, rules with tolerances, users/roles, etc. The user reacted
well to its visual language and asked to bring that look into the real app. Most of the
mockup's *functionality* has no backend support and is out of scope here (see Out of Scope);
this spec covers only the visual/component refactor of pages that already exist and work.

Investigation surfaced one concrete bug along the way: `Table.scala` uses classes
(`bg-neutral-primary-soft`, `text-body`, `border-default`, `bg-neutral-secondary-soft`) that
are not defined anywhere in `tailwind.config.js` — leftovers from the project's original
Tyrian+Flowbite scaffold. Tailwind does not generate CSS for undefined color tokens, so every
table in the app currently renders with no styling at all. Fixing this is part of the `Table`
rewrite below.

## Scope

**In scope** (pages that get restyled): `FundFlowPage` (Accounts tab + Configuration tab
diagram), `ReconSpecsPage`, `ReconciliationsPage`, `ManualMatchPage`,
`UnreconciledMovementsPage`, plus the shared components and nav shell they depend on.

**Out of scope**: `JobsPage`, `EventsPage`, `LoginPage`, `WelcomePage`, all `Maintain*` form
pages — visually untouched in this pass (may get their own pass later). No backend or API
changes. No new domain functionality (see below).

## Design Tokens

Add to `tailwind.config.js` under `theme.extend.colors`, alongside the existing `md-*` keys
(which currently have no dark-mode variants at all):

| Token | Light | Dark | Usage |
|---|---|---|---|
| `md-primary` | `#6d28d9` | `#a78bfa` | *(replaces current `#6750a4`)* |
| `md-primary-hover` | `#5b21b6` | `#c4b0fb` | *(replaces current `#5b4397`)* |
| `md-on-primary` | `#ffffff` | `#211a3d` | |
| `md-primary-container` | `#f1ecfd` | `#362a5c` | |
| `md-on-primary-container` | `#4c1d95` | `#ede9fe` | *(new)* |
| `md-surface-alt` | `#f1eefa` | `#221e35` | *(new — card/table-header background)* |
| `md-good` / `md-good-bg` | `#0ca30c` / `#e4f6e4` | `#3fcf5f` / `#163821` | *(new — status pill)* |
| `md-warning` / `md-warning-bg` | `#a86400` / `#fdf0d2` | `#f0b13a` / `#3c2f10` | *(new)* |
| `md-serious` / `md-serious-bg` | `#c1502a` / `#fbe6dc` | `#f0916a` / `#3d2415` | *(new)* |
| `md-critical` / `md-critical-bg` | `#d03b3b` / `#fbe2e2` | `#ef6f6f` / `#3a1717` | *(new)* |

`md-error` (form validation) is unchanged — status colors are a separate palette from form
error state, matching the mockup's separation of semantic status from accent/error.

Since `tailwind.config.js` currently declares no dark-mode values for any `md-*` token, dark
variants above are wired via Tailwind's `dark:` variant prefix at each call site (e.g.
`bg-md-primary dark:bg-md-primary-dark`), following the same pattern the nav-consistency work
already established for `LeftNav`/`LoginPage`. Money/reference-ID cells additionally get
`font-variant-numeric: tabular-nums` (via a small `.num` utility class in `index.css`) so
columns of amounts align.

## Shared Components

### `Table.scala` (rewrite)

Replaces the broken `bg-neutral-primary-soft` / `text-body` / `border-default` classes with
real tokens: `bg-white dark:bg-gray-800 border border-md-outline rounded-lg shadow-sm`,
sticky `bg-md-surface-alt` header row. Adds an optional parameter:

```scala
def create(headers: Seq[String], rows: Seq[Seq[HtmlElement]], numCols: Set[Int] = Set.empty): HtmlElement
```

Columns whose index is in `numCols` get `text-right` + `.num` (tabular-nums) on both header
and body cells. Existing call sites are unaffected (default empty set); in-scope pages pass
the numeric column indices explicitly (e.g. `Balance`, `Amount`, `Aging Limit`).

### `Pill.scala` (new)

```scala
object Pill:
  enum Tone: case Good, Warning, Serious, Critical, Neutral, Accent
  def apply(text: String, tone: Tone): HtmlElement
```

Renders `span(cls := "...", text)` with the tone's `md-*-bg` background and `md-*` text
color, matching the mockup's pill treatment. Used for reconciliation `status` and account
`isRecon`/SLA-style indicators.

### `Card.scala` (new)

```scala
object Card:
  def apply(content: HtmlElement*): HtmlElement =
    div(cls := "bg-white dark:bg-gray-800 border border-md-outline dark:border-gray-700 rounded-lg shadow-sm", content)
```

Thin wrapper used to replace ad hoc `div` wrappers around page content sections (e.g. around
`Table.create(...)` calls) so content reads as a bounded surface rather than floating on the
page background.

## Nav Shell

### `LeftNav.scala`

Regroup the existing flat list into labeled sections, reusing existing icons (no new SVGs
needed):

| Group | Item | Route | Icon |
|---|---|---|---|
| Overview | Home | `/` (existing) | `home` |
| Reconcile | Ledgers | `/components/fund-flows` (existing) | `document` |
| Reconcile | Exceptions & Disputes | `/components/coming-soon/exceptions` *(new placeholder)* | `document` |
| Configure | Recon Rules | `/components/coming-soon/recon-rules` *(new placeholder)* | `settings` |
| Configure | Jobs | `/components/jobs` (existing, moved from flat list) | `chart` |
| Insights | Reports & Analytics | `/components/coming-soon/reports` *(new placeholder)* | `chart` |
| Insights | Events | `/components/events` (existing, moved) | `document` |
| Admin | Users & Roles | `/components/coming-soon/users` *(new placeholder)* | `user` |
| Admin | Settings | `/components/coming-soon/settings` *(new placeholder)* | `settings` |

`Reconciliations`, `Manual Match`, and `Unreconciled Items` are deliberately **not** added
here — those pages require an `accountId` (and, for the first two, a selected
`AppState.editReconSpec`) that the sidebar has no way to supply. They remain reachable exactly
as today: contextual buttons inside `FundFlowPage`/`ReconSpecsPage`/`UnreconciledMovementsPage`.
Adding top-level links that don't actually work without prior context would misrepresent the
app's structure.

### `ComingSoonPage.scala` (new)

One shared page, parameterized by title, used for all five placeholder routes above:

```scala
object ComingSoonPage:
  def apply(title: String): HtmlElement
```

Centered `PageHeader.title(title)` + a short "This area isn't built yet" message. No fake
data, no charts.

### Topbar

There is currently no dark-mode toggle anywhere in the UI: `AppState.darkMode` is a
`Var(false)` that nothing ever sets, and `Icons.sun`/`Icons.moon` are defined but unused. This
refactor adds the first one — a button in a new slim topbar strip in `MainLayout.scala` (above
the page content, matching the mockup's shell structure) that flips `AppState.darkMode` and
swaps between `Icons.sun`/`Icons.moon`. No search box: there is no backend search endpoint,
and a non-functional search input would mislead users into thinking it works.

## Per-Page Changes

- **`FundFlowPage`**: Accounts tab table passes `numCols` for Cash/Reconciled/Unreconciled
  Balance and Unreconciled Count; `isRecon` badge and future status indicators use `Pill`.
  Configuration tab: the hand-built SVG diagram's hardcoded `#7c3aed`/`#f3f0ff` account-node
  colors are replaced with the new `md-primary`/`md-primary-container` token values (via CSS
  custom properties passed into the generated SVG string, since it's built outside Laminar's
  `cls :=` mechanism).
- **`ReconSpecsPage`**: table wrapped in `Card`; `relationship` rendered as a `Pill` (tone
  `Accent`); `Aging Limit` in `numCols`.
- **`ReconciliationsPage`**: `status` rendered via `Pill`. The backend has no distinct
  "written off" status — `ManualReconciler.writeOff` sets `status = Final` with
  `adjustmentReason` populated (confirmed in `ManualReconciler.scala:83-84`) — so the mapping
  derives the mockup's four-way distinction from fields already on `ReconciliationPayload`:
  `InProgress`→Warning "In Progress", `Final` with `adjustmentReason` empty→Good "Final",
  `Final` with `adjustmentReason` set→Serious "Written Off", `Voided`→Neutral "Voided". No new
  data, just a more informative label than the raw status string. `Balance` in `numCols`.
  Write-off/unmatch controls stay inline in the Actions cell as today — no drawer.
- **`ManualMatchPage`**: In/Out movement tables wrapped in `Card`; `Amount` in `numCols`. No
  suggested-matches panel.
- **`UnreconciledMovementsPage`**: table wrapped in `Card`; `Amount` in `numCols`. No aging
  column.

## Out of Scope

- Dashboard, KPI tiles, charts, sparklines — no aggregate/trend endpoints exist.
- Exceptions & Disputes, Reports & Analytics, Recon Rules (global list), Users & Roles,
  Settings — placeholder pages only, no real screens.
- Approval/maker-checker workflow, comments, attachments, audit-history drawer — none of this
  data exists in the backend.
- Suggested/confidence-scored matches — no matching-confidence data exists.
- Aging buckets on unreconciled movements — `MovementPayload` has no timestamp field to
  compute age from.
- Reconciliation detail slide-over drawer — kept as inline row actions to minimize interaction
  changes in this pass.
- `JobsPage`, `EventsPage`, `LoginPage`, `WelcomePage`, `Maintain*` pages — unchanged visually.
- Global search — no backend endpoint.
