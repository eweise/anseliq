# Frontend Navigation & Design Consistency

**Date:** 2026-08-11

## Goal

Fix inconsistent look-and-feel and broken navigation across the frontend, found via audit. The
2026-04-08 Material Design 3 spec intentionally scoped its token system to MaintainFundFlow only
("no page-specific layout changes outside MaintainFundFlow"); that follow-up propagation to the
rest of the app never happened. This closes that gap and removes dead/orphaned navigation.

## Findings (from audit)

1. `LeftNav.scala` has 5 dead nav items pointing to `"#"` (Dashboard, Profile, Settings,
   Analytics, Reports) and a commented-out `fundFlowNavSection()` call.
2. `AccountsPage.scala` (`/components/accounts`) is a redundant, orphaned duplicate of
   `FundFlowPage`'s "Accounts" tab, which is more complete and already reachable via nav.
   `Nav.toAccounts()` is never called anywhere.
3. The `md-*` design tokens (from the 2026-04-08 spec) are used in 12 files but bypassed with raw
   Tailwind colors (`purple-*`, `blue-*`, `gray-*`) in 6 others — critically `LeftNav.scala` (on
   every page) and `LoginPage.scala`, plus one raw `bg-blue-600` button in `FundFlowPage.scala`'s
   Configuration tab.
4. Page header/spacing drifted across "list" pages: title size wobbles between `text-2xl` and
   `text-xl` (`FundFlowsPage` is the outlier), and top padding (`pt-20`, to clear the fixed
   nav/error banner) is applied ad hoc per page rather than shared.
5. `WelcomePage`/`LoginPage` follow neither the list-page nor the maintain-page convention (`h1`/
   `h5` instead of `h2`, no `pt-20`, `LoginPage` has no dark-mode classes).

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| `AccountsPage.scala` | Delete it, its route, and `Nav.toAccounts` | Redundant with `FundFlowPage`'s Accounts tab, which is more complete and already reachable |
| Dead nav stubs | Remove the 5 `"#"` items and the commented-out line | Pure clutter; building real Dashboard/Profile/Settings/Analytics/Reports pages is a separate feature decision, not a polish task |
| Color tokens | Migrate `LeftNav`, `LoginPage`, and the `FundFlowPage` Configuration-tab button onto existing `md-*` tokens | Matches the pattern `Buttons.scala`/`Form.scala` already use; no new tokens needed |
| List-page headers | Extract one shared header component, use it on every list-style page | Directly fixes the `text-xl`/`text-2xl` drift and the ad hoc `pt-20` repetition |
| `WelcomePage`/`LoginPage` | Bring spacing/typography in line with the shared header convention; add dark-mode classes to `LoginPage` | Consistency; `MainLayout` already supports dark mode everywhere else |
| "Maintain" form pages | No changes | Already consistent per the 2026-04-08 spec; out of scope |

## `PageHeader` Component

Page outer-wrapper structure actually varies for good reason (`EventsPage`/`JobsPage` use a
`max-w-5xl` inner content wrapper; `ReconciliationsPage`/`ManualMatchPage`/
`UnreconciledMovementsPage` don't; `FundFlowsPage` puts its title next to a "Create" button in a
`justify-between` row rather than stacked). Forcing all of them through one rigid wrapper
component would restructure working layouts to fix what is actually only a title-level drift.

Scope `PageHeader` narrowly to just the title element — the one thing that's actually
inconsistent (`text-2xl` everywhere except `FundFlowsPage`'s `text-xl`):

```scala
object PageHeader:
  def title(text: String): HtmlElement = h2(cls := "text-2xl font-medium mb-6", text)
```

Each page keeps its own wrapper/layout; only the `h2(cls := "text-2xl font-medium mb-6", ...)` /
`h2(cls := "text-xl font-medium", ...)` call is replaced with `PageHeader.title(...)`.

## Files Changed

1. `frontend/src/main/scala/onparity/view/layout/LeftNav.scala` — remove dead items, migrate to `md-*` tokens
2. `frontend/src/main/scala/onparity/view/pages/AccountsPage.scala` — delete
3. `frontend/src/main/scala/onparity/routing/Routes.scala` — remove the `accounts` route (list route only; `accounts/:id/...` sub-routes stay)
4. `frontend/src/main/scala/onparity/routing/Nav.scala` — remove `toAccounts`
5. `frontend/src/main/scala/onparity/view/pages/LoginPage.scala` — migrate to `md-*` tokens, add dark-mode classes, align header/spacing
6. `frontend/src/main/scala/onparity/view/pages/WelcomePage.scala` — align header/spacing
7. `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala` — fix the raw `bg-blue-600` "New Account" button; adopt `PageHeader.title` for its already-correct title (DRY, zero visual change)
8. New: `frontend/src/main/scala/onparity/view/components/PageHeader.scala` — shared title helper (see above)
9. Adopt `PageHeader.title` in: `EventsPage.scala`, `FundFlowsPage.scala` (fixes its `text-xl` drift), `JobsPage.scala`, `ManualMatchPage.scala`, `ReconciliationsPage.scala`, `UnreconciledMovementsPage.scala`

## Out of Scope

- Building real Dashboard/Profile/Settings/Analytics/Reports pages
- Changes to the "Maintain" form pages (`MaintainAccountPage`, `MaintainFundFlowPage`,
  `MaintainReconSpecPage`, `MaintainMovementSpecPage`, `IngestPage`) — already consistent
- Any backend/API changes
- Visual redesign beyond consistency (e.g. new color palette, new layout paradigm)
