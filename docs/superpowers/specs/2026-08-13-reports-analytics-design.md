# Reports & Analytics — Design Spec

## Purpose

An ops/reconciler daily health-check dashboard: "is everything on track today?" — not a
trend/historical view. Reachable from the existing "Reports & Analytics" left-nav item, which
currently points at a `ComingSoonPage` stub.

Three widgets, chosen to answer that question directly and to build on data that already exists:

1. **Match rate** — how much of each account's balance is reconciled, right now.
2. **Exception breakdown** — what's open in Exceptions & Disputes, by category and status.
3. **Unreconciled aging** — how long unreconciled movements have been sitting, and how many have
   blown past their account's configured aging limit.

Explicitly out of scope for this version: any time-series/trend chart (e.g. dispute
resolution-time trend), since the audience is "today's snapshot," not "how are we trending."
Dispute resolution-time itself (time from raise to Resolved) is also out of scope — a real
metric, but not selected for v1.

## Widget definitions

### Match rate

Per account: `reconciledBalance / balance × 100`. Both fields already exist on `AccountPayload`
(`common/model/Payload.scala`) — no new computation, just reading and aggregating existing data.
An account with `balance == 0` has no meaningful ratio (avoid divide-by-zero, and treating it as
either 0% or 100% would be misleading) — its `matchRatePercent` is `None`, it's excluded from the
aggregate average, and its own bar renders "—" instead of a percentage.

Rendered as one horizontal bar per account (Chart.js horizontal bar chart), plus an aggregate
"all accounts" figure as a KPI number above the chart.

### Exception breakdown

Counts of **open** disputes (`status != Resolved`) grouped by `category`
(`AmountMismatch`/`MissingCounterpart`/`Duplicate`/`TimingDifference`/`DataQuality`), rendered as
a doughnut chart with a legend. A secondary row of status counts (New/Investigating/Escalated) is
shown as plain count + `Pill` next to the chart, reusing the existing `Pill.Tone` mapping from
`ExceptionsPage`/`ExceptionDetailPage`.

Source: `GET /api/v1/disputes` data, grouped — no new backend query logic beyond what
`DisputeRepo.findAll` already provides.

### Unreconciled aging

The one widget needing new backend capability, because `Movement.createdAt` (present in the
domain model) isn't exposed on `MovementPayload` today, and nothing currently returns unreconciled
movements across *all* accounts in one call.

For every unreconciled movement (`reconciliationId == None`) in scope:
- `ageDays = daysBetween(movement.createdAt, now)`
- Bucketed into fixed ranges for the chart: `0-3`, `4-7`, `8-14`, `15+` (Chart.js bar chart, one
  bar per bucket, count of movements).
- Separately: `overdue = ageDays > account's ReconciliationSpec.agingLimit` (looked up via the
  movement's account's recon spec). The total overdue count is shown as a KPI number ("N overdue")
  above the bucket chart, colored via the existing critical/warning tone convention. An account
  with no recon spec (shouldn't happen for a recon account, but the aging computation must not
  throw if one is missing) is excluded from the overdue count, not from the bucket chart.

## Scope: global with a fund-flow filter

The page defaults to all fund flows/accounts. A `<select>` at the top (populated from
`GET /api/v1/fund-flows`, default "All") narrows every widget to one fund flow by passing
`fundFlowId` to the summary endpoint. This matches the left-nav item's placement outside any
single fund flow's context.

## Backend

**New endpoint:** `GET /api/v1/reports/summary?fundFlowId=<optional UUID>`

**New payload** (`common/model/Payload.scala`):

```scala
case class MatchRatePayload(
  accountId:   String,
  accountName: String,
  balance:     Money,
  reconciledBalance: Money,
  matchRatePercent: Option[BigDecimal] // None when balance == 0
) derives ReadWriter

case class ExceptionBreakdownPayload(
  byCategory: Map[String, Int], // DisputeCategoryPayload.toString -> count, open disputes only
  byStatus:   Map[String, Int]  // DisputeStatusPayload.toString -> count, open disputes only
) derives ReadWriter

case class AgingBucketsPayload(
  buckets: Seq[(String, Int)], // ("0-3", "4-7", "8-14", "15+") -> count, in that fixed order
  overdueCount: Int
) derives ReadWriter

case class ReportsSummaryPayload(
  matchRates: Seq[MatchRatePayload],
  exceptions: ExceptionBreakdownPayload,
  aging:      AgingBucketsPayload
) derives ReadWriter
```

`MovementPayload` gains a `createdAt: OffsetDateTime` field (additive; populated from the domain
model's existing `Movement.createdAt`, which is always set for persisted movements).

**New service**, `backend/src/main/scala/perfectpair/domain/service/ReportingService.scala`:

```scala
class ReportingService(using
  accountRepo:   AccountRepo,
  disputeRepo:   DisputeRepo,
  movementRepo:  MovementRepo,
  reconSpecRepo: ReconciliationSpecRepo,
  clock:         ClockService
):
  def summary(fundFlowId: Option[ID]): Txn[ReportsSummaryReport] = ...
```

Returns a domain-level report struct; the route's `Payload.reportsSummaryPayloadFromDomain(...)`
converts it, following the existing `adapter/driving/Payload.scala` conversion-function pattern.

Internally, for this version, `ReportingService` queries `AccountRepo`/`DisputeRepo`/
`MovementRepo`/`ReconciliationSpecRepo` directly and computes everything in Scala — no new SQL
aggregation, no new tables. This is a deliberate simplification for now.

**Future work (explicitly out of scope for this implementation):** the reporting data will
eventually be aggregated periodically into dedicated reporting tables rather than computed live
from the operational tables on every request. `ReportingService.summary(fundFlowId)` is the
intended seam for that change — a future version can swap its internals to read from aggregated
tables without changing the route or the frontend contract. Nothing in this version should make
that swap harder than it needs to be (e.g., avoid leaking live-query-specific details into the
payload shape).

## Frontend

- `frontend/src/main/scala/perfectpair/view/pages/ReportsPage.scala` (new), reachable at a new
  `reports` route (`path("reports")(ReportsPage())`), replacing the `ComingSoonPage` stub.
  `LeftNav`'s "Reports & Analytics" entry is updated to point at `/components/reports` instead of
  `/components/coming-soon/reports`.
- Fund-flow `<select>` at the top, default "All", re-fetching
  `GET /api/v1/reports/summary[?fundFlowId=...]` on change.
- Three widget cards, each a `div` with a `card-header`-style title (matching existing card
  conventions in e.g. `ExceptionDetailPage`'s target-details box) and a `ChartCanvas` inside:
  - Match rate: horizontal bar chart, one bar per account, plus the aggregate KPI number.
  - Exceptions: doughnut chart (by category) + legend, with status-count `Pill`s alongside.
  - Aging: bar chart (fixed buckets) + "N overdue" KPI number above it.

**Chart.js integration:**
- `chart.js` added to `frontend/package.json` as a dependency (bundled via the existing Vite
  setup — no new build tooling).
- `frontend/src/main/scala/perfectpair/http/ChartJs.scala` (new): a slim hand-written facade —
  `@js.native @JSImport("chart.js/auto", JSImport.Namespace) class Chart(ctx: js.Any, config: js.Any) extends js.Object`
  plus a `destroy(): Unit` method. No ScalablyTyped; config objects are passed as
  `js.Dynamic`/`js.Any` and are not compile-time type-checked.
- `frontend/src/main/scala/perfectpair/view/components/ChartCanvas.scala` (new): a reusable
  Laminar component taking a chart-type string, a `js.Dynamic` data/options config, and rendering
  a `<canvas>`. On mount, constructs a `Chart` instance against the canvas. Because Chart.js draws
  directly to canvas and can't respond to Tailwind's `dark:` classes, the component reads
  `AppState.darkMode.signal` and **destroys and reconstructs** the `Chart` instance (not just
  updates its data) whenever either the underlying data or the dark-mode flag changes, passing
  explicit light/dark colors into the config each time. Destroys the `Chart` instance on unmount
  to avoid leaking canvas contexts when navigating away.

## Error handling

- `ReportingService.summary` on an unknown `fundFlowId` throws `NotFoundError`, consistent with
  every other `findById(...).ErrIfNotFound(...)` usage in this codebase — surfaces as an
  (unhandled, pre-existing app-wide gap) 500 today, same as every other route.
- A movement whose account has no matching `ReconciliationSpec` is skipped for the overdue count
  (see "Unreconciled aging" above) rather than throwing, since this is a data-consistency edge
  case, not a user error.
- Frontend: fetch failures populate `AppState.globalError`, consistent with every other page.

## Testing

**Backend:** `ReportingServiceSuite` (new), covering:
- Match rate calculation, including the `balance == 0` → `None` case.
- Exception breakdown grouping (by category and by status), confirming Resolved disputes are
  excluded.
- Aging bucket assignment at each boundary (3/4, 7/8, 14/15 days).
- Overdue count respects each account's own `agingLimit`, and a movement whose account has no
  recon spec doesn't throw and isn't counted as overdue.
- `fundFlowId` filter narrows all three widgets to that fund flow's accounts only.

**Frontend:** no unit tests for the Chart.js interop layer (matches this app's existing pattern of
no frontend unit tests). Verified live via Playwright screenshots in both light and dark mode,
including toggling dark mode after the page has loaded (to confirm the chart redraws with correct
colors rather than only picking up the mode active at initial render).
