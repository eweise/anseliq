# Reports & Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an ops daily health-check dashboard at `/components/reports` showing match rate, open-exception breakdown, and unreconciled aging, each as a Chart.js visualization, with an optional fund-flow filter.

**Architecture:** A new backend `ReportingService` computes all three widgets by querying existing repos directly (no new tables, no new SQL aggregation) behind one `GET /api/v1/reports/summary` endpoint. The frontend gets a slim hand-written Chart.js facade (no ScalablyTyped) wrapped in a reusable `ChartCanvas` Laminar component, and a new `ReportsPage` that composes three widget cards around it.

**Tech Stack:** Scala 3 backend (Magnum/cask), Scala.js/Laminar frontend, Chart.js (new npm dependency, manual `@js.native` facade), existing Vite bundling.

## Global Constraints

- No ScalablyTyped — Chart.js is wrapped with a hand-written `@js.native`/`@JSImport` facade only (decided during brainstorming; the config object is passed as untyped `js.Dynamic`/`js.Any`).
- `ReportingService` queries existing operational tables directly for this version — no new reporting tables, no scheduled aggregation job. This is a deliberate simplification; `ReportingService.summary(fundFlowId)` is the seam for a future swap to precomputed aggregates, so avoid leaking live-query-specific details into `ReportsSummaryPayload`'s shape.
- No time-series/trend widgets in this version (out of scope per the design spec).

---

### Task 1: Domain report models + `ReportingService`

**Files:**
- Create: `backend/src/main/scala/perfectpair/domain/model/ReportsSummary.scala`
- Create: `backend/src/main/scala/perfectpair/domain/service/ReportingService.scala`
- Test: `backend/src/test/scala/perfectpair/domain/service/ReportingServiceSuite.scala`

**Interfaces:**
- Consumes: `AccountRepo.findAll()`/`findByFundFlow(ID)` (existing), `DisputeRepo.findAll` (existing, inherited from `DomainRepo`), `MovementRepo.findUnReconciledByAccount(ID)`/`findById(ID)` (existing), `ReconciliationRepo.findById(ID)` (existing), `ReconciliationSpecRepo.findByAccountID(ID)`/`findById(ID)` (existing), `FundFlowConfigRepo.findById(ID)` (existing), `ClockService.now` (existing).
- Produces: `ReportingService.summary(fundFlowId: Option[ID]): Txn[ReportsSummaryReport]`, and the domain report types `MatchRateReport`, `ExceptionBreakdownReport`, `AgingBucketsReport`, `ReportsSummaryReport` — all consumed by Task 3's route.

Account scoping: `MatchRateReport`/`AgingBucketsReport` only cover accounts where `isRecon == true` (non-recon accounts don't carry a meaningful reconciled/unreconciled split). `ExceptionBreakdownReport` covers open disputes (`status != Resolved`) whose target resolves to an account within the fund-flow scope.

- [ ] **Step 1: Write the domain report model**

```scala
// backend/src/main/scala/perfectpair/domain/model/ReportsSummary.scala
package perfectpair.domain.model

import perfectpair.common.model.model.{ID, Money}

case class MatchRateReport(
  accountId:         ID,
  accountName:       String,
  balance:           Money,
  reconciledBalance: Money,
  matchRatePercent:  Option[BigDecimal] // None when balance == 0
)

case class ExceptionBreakdownReport(
  byCategory: Map[String, Int], // DisputeCategory.toString -> count, open disputes only
  byStatus:   Map[String, Int]  // DisputeStatus.toString -> count, open disputes only
)

case class AgingBucketsReport(
  buckets:      Seq[(String, Int)], // ("0-3", "4-7", "8-14", "15+") -> count, in that fixed order
  overdueCount: Int
)

case class ReportsSummaryReport(
  matchRates: Seq[MatchRateReport],
  exceptions: ExceptionBreakdownReport,
  aging:      AgingBucketsReport
)
```

- [ ] **Step 2: Write the failing tests**

```scala
// backend/src/test/scala/perfectpair/domain/service/ReportingServiceSuite.scala
package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.DbDataSource
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class ReportingServiceSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def createMovement(inAccountId: ID, outAccountId: ID, movementSpecId: ID, amount: BigDecimal)(using data: DataSetup): Movement =
    val m = Movement(
      inAccountId    = inAccountId,
      outAccountId   = outAccountId,
      movementSpecId = movementSpecId,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  def createMovementAgedDays(
    inAccountId:    ID,
    outAccountId:   ID,
    movementSpecId: ID,
    ageDays:        Long
  )(using data: DataSetup, clock: ClockService): Movement =
    val m    = createMovement(inAccountId, outAccountId, movementSpecId, 100)
    val aged = m.copy(createdAt = Some(clock.now.minusDays(ageDays)))
    data.movementRepo.update(aged)
    aged

  test("matchRates: computes reconciledBalance/balance*100, excludes zero-balance accounts from the ratio"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true, balance = 300, reconciledBalance = 100))
        val zeroBalance = Account(name = "zero-balance-recon", fundFlowId = data.fundFlowConfig.id, isRecon = true)
        data.accountRepo.insert(zeroBalance)

        val result = service.summary(None)

        val recon = result.matchRates.find(_.accountId == data.reconAccount.id).get
        assertEquals(recon.matchRatePercent, Some(BigDecimal("33.33")))

        val zero = result.matchRates.find(_.accountId == zeroBalance.id).get
        assertEquals(zero.matchRatePercent, None)

  test("matchRates: only includes accounts where isRecon == true"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        // data.reconAccount is isRecon = false by default; data.journalAccount too
        val result = service.summary(None)

        assert(!result.matchRates.exists(_.accountId == data.reconAccount.id))
        assert(!result.matchRates.exists(_.accountId == data.journalAccount.id))

  test("exceptions: groups open disputes by category and status, excludes Resolved"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        val m = createMovement(data.journalToRecon.inAccountId, data.journalToRecon.outAccountId, data.journalToRecon.id, 100)
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.AmountMismatch, status = DisputeStatus.New, createdAt = Some(clock.now), updatedAt = Some(clock.now)))
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.Duplicate, status = DisputeStatus.Investigating, createdAt = Some(clock.now), updatedAt = Some(clock.now)))
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.AmountMismatch, status = DisputeStatus.Resolved, createdAt = Some(clock.now), updatedAt = Some(clock.now)))

        val result = service.summary(None)

        assertEquals(result.exceptions.byCategory.get("AmountMismatch"), Some(1))
        assertEquals(result.exceptions.byCategory.get("Duplicate"), Some(1))
        assertEquals(result.exceptions.byStatus.get("New"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Investigating"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Resolved"), None)

  test("aging: assigns unreconciled movements to fixed day buckets at each boundary"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true))
        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // 0-3
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 4)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 7)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 8)  // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 14) // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 15) // 15+

        val result = service.summary(None)
        val buckets = result.aging.buckets.toMap

        assertEquals(buckets("0-3"), 1)
        assertEquals(buckets("4-7"), 2)
        assertEquals(buckets("8-14"), 2)
        assertEquals(buckets("15+"), 1)

  test("aging: overdueCount respects the account's agingLimit; an account with no recon spec is skipped, not thrown"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true))
        data.reconSpecRepo.update(data.reconSpec.copy(agingLimit = 5))
        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // within limit
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 10) // overdue

        val orphanAccount = Account(name = "orphan-recon", fundFlowId = data.fundFlowConfig.id, isRecon = true)
        data.accountRepo.insert(orphanAccount)
        val orphanSpec = MovementSpec(inAccountId = orphanAccount.id, outAccountId = data.journalAccount.id)
        data.movementSpecRepo.insert(orphanSpec)
        createMovementAgedDays(orphanAccount.id, data.journalAccount.id, orphanSpec.id, 20) // no recon spec exists for orphanAccount

        val result = service.summary(None) // must not throw

        assertEquals(result.aging.overdueCount, 1)

  test("summary(fundFlowId) scopes matchRates and aging to that fund flow's accounts only"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true, balance = 100, reconciledBalance = 50))

        val otherFundFlow = FundFlowConfig(name = "other-ff")
        data.fundFlowConfigRepo.insert(otherFundFlow)
        val otherAccount = Account(name = "other-recon", fundFlowId = otherFundFlow.id, isRecon = true, balance = 200, reconciledBalance = 200)
        data.accountRepo.insert(otherAccount)

        val scoped = service.summary(Some(data.fundFlowConfig.id))
        assertEquals(scoped.matchRates.map(_.accountId), Seq(data.reconAccount.id))

        val unscoped = service.summary(None)
        assert(unscoped.matchRates.exists(_.accountId == otherAccount.id))

  test("summary with an unknown fundFlowId throws NotFoundError"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        intercept[NotFoundError]:
          service.summary(Some(java.util.UUID.randomUUID()))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReportingServiceSuite"`
Expected: FAIL to compile — `ReportingService` doesn't exist yet.

- [ ] **Step 4: Implement `ReportingService`**

```scala
// backend/src/main/scala/perfectpair/domain/service/ReportingService.scala
package perfectpair.domain.service

import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

import java.time.temporal.ChronoUnit

class ReportingService(using
  accountRepo:       AccountRepo,
  disputeRepo:        DisputeRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
  clock:              ClockService
):
  private val agingBucketOrder = Seq("0-3", "4-7", "8-14", "15+")

  def summary(fundFlowId: Option[ID]): Txn[ReportsSummaryReport] =
    fundFlowId.foreach(id => fundFlowConfigRepo.findById(id).ErrIfNotFound("fund flow", id))

    val accounts = fundFlowId match
      case Some(id) => accountRepo.findByFundFlow(id)
      case None     => accountRepo.findAll()
    val reconAccounts = accounts.filter(_.isRecon)

    ReportsSummaryReport(
      matchRates = matchRates(reconAccounts),
      exceptions = exceptionBreakdown(fundFlowId, accounts.map(_.id).toSet),
      aging      = agingBuckets(reconAccounts)
    )

  private def matchRates(reconAccounts: Vector[Account]): Seq[MatchRateReport] =
    reconAccounts.map { a =>
      MatchRateReport(a.id, a.name, a.balance, a.reconciledBalance, matchRatePercent(a.balance, a.reconciledBalance))
    }

  private def matchRatePercent(balance: Money, reconciledBalance: Money): Option[BigDecimal] =
    if balance.signum == 0 then None
    else
      val ratio = BigDecimal(reconciledBalance.bigDecimal.divide(balance.bigDecimal, 10, java.math.RoundingMode.HALF_UP))
      Some((ratio * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))

  private def exceptionBreakdown(fundFlowId: Option[ID], accountIdsInScope: Set[ID]): ExceptionBreakdownReport =
    val allOpen = disputeRepo.findAll.filter(_.status != DisputeStatus.Resolved)
    val inScope = fundFlowId match
      case None    => allOpen
      case Some(_) => allOpen.filter(d => disputeAccountId(d).exists(accountIdsInScope.contains))

    ExceptionBreakdownReport(
      byCategory = inScope.groupBy(_.category).map((k, v) => k.toString -> v.size),
      byStatus   = inScope.groupBy(_.status).map((k, v) => k.toString -> v.size)
    )

  private def disputeAccountId(d: Dispute): Option[ID] =
    d.targetType match
      case DisputeTarget.Movement =>
        movementRepo.findById(d.referenceId).map(_.inAccountId)
      case DisputeTarget.Reconciliation =>
        for
          recon <- reconRepo.findById(d.referenceId)
          spec  <- reconSpecRepo.findById(recon.specId)
        yield spec.accountId

  private def agingBuckets(reconAccounts: Vector[Account]): AgingBucketsReport =
    val counts  = scala.collection.mutable.Map(agingBucketOrder.map(_ -> 0)*)
    var overdue = 0

    reconAccounts.foreach { account =>
      val agingLimit = reconSpecRepo.findByAccountID(account.id).headOption.map(_.agingLimit)
      movementRepo.findUnReconciledByAccount(account.id).foreach { m =>
        val ageDays = ChronoUnit.DAYS.between(m.createdAt.get, clock.now)
        val bucket =
          if ageDays <= 3 then "0-3"
          else if ageDays <= 7 then "4-7"
          else if ageDays <= 14 then "8-14"
          else "15+"
        counts(bucket) += 1
        agingLimit.foreach(limit => if ageDays > limit then overdue += 1)
      }
    }

    AgingBucketsReport(agingBucketOrder.map(b => b -> counts(b)), overdue)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReportingServiceSuite"`
Expected: all 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/model/ReportsSummary.scala backend/src/main/scala/perfectpair/domain/service/ReportingService.scala backend/src/test/scala/perfectpair/domain/service/ReportingServiceSuite.scala
git commit -m "Add ReportingService: match rate, exception breakdown, unreconciled aging

Computes all three from existing repos directly (no new tables/SQL
aggregation) — a deliberate simplification; summary(fundFlowId) is the
seam for a future move to precomputed reporting tables."
```

---

### Task 2: Shared DTOs + `MovementPayload.createdAt`

**Files:**
- Modify: `common/src/main/scala/perfectpair/common/model/Payload.scala`

**Interfaces:**
- Consumes: nothing from Task 1 (payload types are independent of domain types).
- Produces: `MatchRatePayload`, `ExceptionBreakdownPayload`, `AgingBucketsPayload`, `ReportsSummaryPayload`, and `MovementPayload.createdAt: OffsetDateTime` — consumed by Task 3's `Payload.reportsSummaryPayloadFromDomain`/`movementfromDomainModel` and Task 5's frontend.

- [ ] **Step 1: Add the report DTOs**

Add after the existing `DisputeCommentPayload`/`DisputeDetailPayload`/`AddDisputeCommentRequest` block (end of the Dispute section):

```scala
case class MatchRatePayload(
  accountId:         String,
  accountName:       String,
  balance:           Money,
  reconciledBalance: Money,
  matchRatePercent:  Option[BigDecimal]
) derives ReadWriter

case class ExceptionBreakdownPayload(
  byCategory: Map[String, Int],
  byStatus:   Map[String, Int]
) derives ReadWriter

case class AgingBucketsPayload(
  buckets:      Seq[(String, Int)],
  overdueCount: Int
) derives ReadWriter

case class ReportsSummaryPayload(
  matchRates: Seq[MatchRatePayload],
  exceptions: ExceptionBreakdownPayload,
  aging:      AgingBucketsPayload
) derives ReadWriter
```

`Money` (`= BigDecimal`) is already imported at the top of this file via
`perfectpair.common.model.model.{Money, Properties}`. `BigDecimal` itself needs a `ReadWriter` — upickle
derives one automatically for `BigDecimal` out of the box, matching how `ReconciliationPayload.balance:
Money` already works today without any extra import.

- [ ] **Step 2: Add `createdAt` to `MovementPayload`**

```scala
case class MovementPayload(
  id:               String,
  referenceId:      String,
  outAccountId:     String,
  inAccountId:      String,
  amount:           Money,
  properties:       Properties,
  reconciliationId: String,
  createdAt:        OffsetDateTime
) derives ReadWriter
```

(Only one field added, at the end — `createdAt: OffsetDateTime`.)

- [ ] **Step 3: Type-check**

Run: `sbt commonJVM/compile`
Expected: `[success]`. (This will also surface a compile error in `backend/adapter/driving/Payload.scala`'s
`movementfromDomainModel` once `backend/compile` runs, since it's now missing the new required field —
that's expected and fixed in Task 3.)

- [ ] **Step 4: Commit**

```bash
git add common/src/main/scala/perfectpair/common/model/Payload.scala
git commit -m "Add Reports & Analytics DTOs, add createdAt to MovementPayload"
```

---

### Task 3: Backend routes + wiring

**Files:**
- Modify: `common/src/main/scala/perfectpair/common/http/http.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`
- Modify: `backend/src/main/scala/perfectpair/app/Main.scala`

**Interfaces:**
- Consumes: `ReportingService.summary` (Task 1), `ReportsSummaryPayload` family + `MovementPayload.createdAt` (Task 2).
- Produces: `GET /api/v1/reports/summary?fundFlowId=<optional UUID>` — consumed by Task 5's frontend.

- [ ] **Step 1: Add the route path**

In `HttpPath` (`common/src/main/scala/perfectpair/common/http/http.scala`), add alongside the existing paths:

```scala
  val reports         = s"$prefix/reports"
```

- [ ] **Step 2: Fix `movementfromDomainModel`, add `reportsSummaryPayloadFromDomain`**

In `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala`, add `createdAt` to the existing
`movementfromDomainModel`:

```scala
  def movementfromDomainModel(dmMovement: Movement): MovementPayload =
    MovementPayload(
      id               = dmMovement.id.toString,
      referenceId      = dmMovement.referenceId.toString,
      outAccountId     = dmMovement.outAccountId.toString,
      inAccountId      = dmMovement.inAccountId.toString,
      amount           = dmMovement.amount,
      properties       = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull,
      createdAt        = dmMovement.createdAt.get
    )
```

Add at the end of the file (after `disputeCommentPayloadFromDomain`):

```scala
  def reportsSummaryPayloadFromDomain(r: ReportsSummaryReport): ReportsSummaryPayload =
    ReportsSummaryPayload(
      matchRates = r.matchRates.map { m =>
        MatchRatePayload(
          accountId         = m.accountId.toString,
          accountName       = m.accountName,
          balance           = m.balance,
          reconciledBalance = m.reconciledBalance,
          matchRatePercent  = m.matchRatePercent
        )
      },
      exceptions = ExceptionBreakdownPayload(byCategory = r.exceptions.byCategory, byStatus = r.exceptions.byStatus),
      aging      = AgingBucketsPayload(buckets = r.aging.buckets, overdueCount = r.aging.overdueCount)
    )
```

- [ ] **Step 3: Add the route**

In `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`, add `reportingService:
ReportingService` to `App`'s constructor `using` clause (alongside the existing `disputeService`):

```scala
  disputeCommentRepo: DisputeCommentRepo,
  disputeService:     DisputeService,
  reportingService:   ReportingService
```

Add a new section after the "Disputes" section (before "// Jobs"):

```scala
  // Reports
  @cask.route(s"${HttpPath.reports}/summary", methods = HttpCmd.GET)
  def getReportsSummary(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val report = reportingService.summary(fundFlowId.map(UUID.fromString))
      write(Payload.reportsSummaryPayloadFromDomain(report))
```

- [ ] **Step 4: Wire `ReportingService` into `Main.scala`**

```scala
    given reportingService:  ReportingService       = ReportingService()
```

Add it right after the existing `given disputeService: DisputeService = DisputeService()` line.

- [ ] **Step 5: Type-check**

Run: `sbt backend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 6: Manual smoke test**

Start the backend (`sbt backend/run`), wait for it to respond, then:

```bash
curl -s http://localhost:8080/api/v1/reports/summary
```

Expected: a `ReportsSummaryPayload` JSON body with `matchRates`, `exceptions`, `aging` keys (arrays/objects
may be empty depending on existing data — that's fine, the important thing is a `200` with the right
shape, not a `500`).

```bash
curl -s "http://localhost:8080/api/v1/reports/summary?fundFlowId=00000000-0000-0000-0000-000000000000"
```

Expected: `500` (unknown fund flow → `NotFoundError`, same pre-existing unhandled-exception behavior as
every other route in this app).

Stop the backend when done.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/scala/perfectpair/common/http/http.scala backend/src/main/scala/perfectpair/adapter/driving/Payload.scala backend/src/main/scala/perfectpair/adapter/driving/Routes.scala backend/src/main/scala/perfectpair/app/Main.scala
git commit -m "Add GET /api/v1/reports/summary"
```

---

### Task 4: Frontend Chart.js facade + `ChartCanvas` component

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/main/scala/perfectpair/http/Chart.scala`
- Create: `frontend/src/main/scala/perfectpair/view/components/ChartCanvas.scala`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure frontend infrastructure).
- Produces: `ChartCanvas.Series(labels: Seq[String], values: Seq[Double])`, `ChartCanvas.apply(chartType:
  String, dataSignal: Signal[Series], horizontal: Boolean = false): HtmlElement` — consumed by Task 5's
  `ReportsPage`.

- [ ] **Step 1: Add the npm dependency**

In `frontend/package.json`, add to `dependencies`:

```json
  "dependencies": {
    "flowbite": "^2.4.1",
    "chart.js": "^4.4.7"
  },
```

Run (from `frontend/`): `npm install`
Expected: `package-lock.json` updates to include `chart.js`; `node_modules/chart.js` exists.

- [ ] **Step 2: Write the facade**

```scala
// frontend/src/main/scala/perfectpair/http/Chart.scala
package perfectpair.http

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("chart.js/auto", "Chart")
class Chart(ctx: js.Any, config: js.Any) extends js.Object:
  def destroy(): Unit = js.native
```

`chart.js/auto` (rather than `chart.js`) auto-registers every controller/element/scale Chart.js ships, so
no separate registration boilerplate is needed for the bar and doughnut types this dashboard uses.

- [ ] **Step 3: Write `ChartCanvas`**

```scala
// frontend/src/main/scala/perfectpair/view/components/ChartCanvas.scala
package perfectpair.view.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import perfectpair.http.Chart
import perfectpair.state.AppState

import scala.scalajs.js

object ChartCanvas:

  case class Series(labels: Seq[String], values: Seq[Double])

  private val paletteLight = js.Array("#6d28d9", "#0891b2", "#d97706", "#059669", "#db2777", "#6366f1")
  private val paletteDark  = js.Array("#a78bfa", "#22d3ee", "#fbbf24", "#34d399", "#f472b6", "#818cf8")

  def apply(chartType: String, dataSignal: Signal[Series], horizontal: Boolean = false): HtmlElement =
    var chartInstance: Option[Chart] = None

    def render(canvas: dom.HTMLCanvasElement, series: Series, dark: Boolean): Unit =
      chartInstance.foreach(_.destroy())

      val textColor = if dark then "#e5e7eb" else "#374151"
      val gridColor = if dark then "#374151" else "#e5e7eb"
      val colors    = if dark then paletteDark else paletteLight

      val scalesConfig: js.Any =
        if chartType == "doughnut" then js.undefined
        else
          js.Dynamic.literal(
            x = js.Dynamic.literal(ticks = js.Dynamic.literal(color = textColor), grid = js.Dynamic.literal(color = gridColor)),
            y = js.Dynamic.literal(ticks = js.Dynamic.literal(color = textColor), grid = js.Dynamic.literal(color = gridColor))
          )

      val config = js.Dynamic.literal(
        `type` = chartType,
        data = js.Dynamic.literal(
          labels = js.Array(series.labels*),
          datasets = js.Array(
            js.Dynamic.literal(
              data            = js.Array(series.values.map(v => v: js.Any)*),
              backgroundColor = colors,
              borderWidth     = 0
            )
          )
        ),
        options = js.Dynamic.literal(
          responsive = true,
          indexAxis  = if horizontal then "y" else "x",
          plugins    = js.Dynamic.literal(legend = js.Dynamic.literal(labels = js.Dynamic.literal(color = textColor))),
          scales     = scalesConfig
        )
      )
      chartInstance = Some(new Chart(canvas.getContext("2d"), config))

    canvasTag(
      heightAttr := 220,
      inContext { el =>
        dataSignal.combineWithFn(AppState.darkMode.signal)((series, dark) => (series, dark)) --> Observer[(Series, Boolean)] {
          case (series, dark) => render(el.ref, series, dark)
        }
      },
      onUnmountCallback { _ => chartInstance.foreach(_.destroy()) }
    )
```

Because Chart.js draws directly to the canvas, it can't pick up Tailwind's `dark:` classes — `ChartCanvas`
reads `AppState.darkMode.signal` itself and destroys/reconstructs the whole `Chart` instance (not just
updates its data) on every emission from either the caller's `dataSignal` or a dark-mode toggle, passing
explicit colors into the config each time. Binding a `Signal` via `-->` (rather than an `EventStream`)
fires immediately with the signal's current value on mount, so the chart renders as soon as both the data
and the current dark-mode state are known — no separate "initial render" step needed.

- [ ] **Step 4: Type-check**

Run: `sbt "frontend/compile"`
Expected: `[success]`. (`ChartCanvas` isn't used anywhere yet, so this only proves it compiles in isolation
— Task 5 exercises it live.)

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/main/scala/perfectpair/http/Chart.scala frontend/src/main/scala/perfectpair/view/components/ChartCanvas.scala
git commit -m "Add a slim Chart.js facade and reusable ChartCanvas Laminar component

Hand-written @js.native facade (no ScalablyTyped) — the config object is
untyped js.Dynamic, but this app only ever constructs two simple chart
shapes (bar, doughnut), so the lack of compile-time API coverage isn't a
meaningful cost. ChartCanvas destroys/reconstructs the Chart instance on
every data or dark-mode change since Chart.js can't see Tailwind classes."
```

---

### Task 5: `ReportsPage` + nav/route wiring

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/pages/ReportsPage.scala`
- Modify: `frontend/src/main/scala/perfectpair/routing/Routes.scala`
- Modify: `frontend/src/main/scala/perfectpair/view/layout/LeftNav.scala`

**Interfaces:**
- Consumes: `ReportsSummaryPayload`/`MatchRatePayload`/`ExceptionBreakdownPayload`/`AgingBucketsPayload`
  (Task 2), `GET /api/v1/reports/summary` (Task 3), `ChartCanvas`/`ChartCanvas.Series` (Task 4).
- Produces: `ReportsPage.apply(): HtmlElement`, reachable at `/components/reports`.

- [ ] **Step 1: Write `ReportsPage`**

```scala
// frontend/src/main/scala/perfectpair/view/pages/ReportsPage.scala
package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{FundFlowPayload, ReportsSummaryPayload}
import perfectpair.http.ApiClient
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.ChartCanvas.Series
import perfectpair.view.components.{ChartCanvas, Form, PageHeader, Pill}
import upickle.default.*

object ReportsPage:

  case class State(
    fundFlows: Seq[FundFlowPayload]          = Seq.empty,
    summary:   Option[ReportsSummaryPayload] = None
  )

  def apply(): HtmlElement =
    val state            = Var(State())
    val loadFundFlowsBus = EventBus[Unit]()
    val loadSummaryBus   = EventBus[Option[String]]()

    def loadFundFlows() =
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.url(HttpPath.fundFlows), SessionStore.token)

    def loadSummary(fundFlowId: Option[String]) =
      val query = fundFlowId.map(id => s"?fundFlowId=$id").getOrElse("")
      ApiClient.get[ReportsSummaryPayload](HttpPath.url(s"${HttpPath.reports}/summary$query"), SessionStore.token)

    div(
      loadFundFlowsBus.events.flatMapSwitch(_ => loadFundFlows()) --> {
        case Right(ffs) => state.update(_.copy(fundFlows = ffs))
        case Left(e)    => AppState.globalError.set(Some(e))
      },
      loadSummaryBus.events.flatMapSwitch(loadSummary) --> {
        case Right(s) => state.update(_.copy(summary = Some(s)))
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ =>
        loadFundFlowsBus.emit(())
        loadSummaryBus.emit(None)
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 w-full max-w-5xl",
        div(
          cls := "flex justify-between items-center mb-6",
          PageHeader.title("Reports & Analytics"),
          child <-- state.signal.map(s => fundFlowSelect(s.fundFlows, loadSummaryBus))
        ),
        child <-- state.signal.map {
          case State(_, None)          => div()
          case State(_, Some(summary)) => widgets(summary)
        }
      )
    )

  private def fundFlowSelect(fundFlows: Seq[FundFlowPayload], loadSummaryBus: EventBus[Option[String]]): HtmlElement =
    val options = ("", "All fund flows") +: fundFlows.map(ff => ff.id.getOrElse("") -> ff.name)
    div(
      cls := "w-64",
      Form.selectField("fundFlowFilter", "Fund flow", options, "", v => loadSummaryBus.emit(Option(v).filter(_.nonEmpty)))
    )

  private def widgets(summary: ReportsSummaryPayload): HtmlElement =
    div(
      cls := "grid grid-cols-1 gap-6",
      matchRateCard(summary),
      exceptionCard(summary),
      agingCard(summary)
    )

  private def matchRateCard(summary: ReportsSummaryPayload): HtmlElement =
    val rated    = summary.matchRates.filter(_.matchRatePercent.isDefined)
    val excluded = summary.matchRates.filterNot(_.matchRatePercent.isDefined)
    val avg      = if rated.isEmpty then None else Some(rated.flatMap(_.matchRatePercent).sum / rated.size)

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Match rate"),
      div(
        cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-3",
        avg match
          case Some(a) => s"${a.setScale(1, BigDecimal.RoundingMode.HALF_UP)}% reconciled across ${rated.size} account(s)"
          case None    => "No reconciling accounts with a balance to measure"
      ),
      ChartCanvas("bar", Val(Series(rated.map(_.accountName), rated.flatMap(_.matchRatePercent).map(_.toDouble))), horizontal = true),
      if excluded.nonEmpty then
        div(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-2",
          s"Excluded (zero balance): ${excluded.map(_.accountName).mkString(", ")}"
        )
      else div()
    )

  private def exceptionCard(summary: ReportsSummaryPayload): HtmlElement =
    val categoryLabels = Seq("AmountMismatch", "MissingCounterpart", "Duplicate", "TimingDifference", "DataQuality")
    val categoryValues = categoryLabels.map(c => summary.exceptions.byCategory.getOrElse(c, 0).toDouble)
    val statusLabels    = Seq("New", "Investigating", "Escalated")

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Open exceptions by category"),
      ChartCanvas("doughnut", Val(Series(categoryLabels, categoryValues))),
      div(
        cls := "flex gap-2 mt-3 flex-wrap",
        statusLabels.map(s => Pill(s"$s: ${summary.exceptions.byStatus.getOrElse(s, 0)}", statusTone(s)))
      )
    )

  private def statusTone(status: String): Pill.Tone = status match
    case "New"           => Pill.Tone.Accent
    case "Investigating" => Pill.Tone.Warning
    case "Escalated"     => Pill.Tone.Critical
    case _                => Pill.Tone.Neutral

  private def agingCard(summary: ReportsSummaryPayload): HtmlElement =
    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Unreconciled aging"),
      div(
        cls := "mb-3",
        Pill(s"${summary.aging.overdueCount} overdue", if summary.aging.overdueCount > 0 then Pill.Tone.Critical else Pill.Tone.Good)
      ),
      ChartCanvas("bar", Val(Series(summary.aging.buckets.map(_._1), summary.aging.buckets.map(_._2.toDouble))))
    )
```

- [ ] **Step 2: Add the route**

In `frontend/src/main/scala/perfectpair/routing/Routes.scala`, replace:

```scala
          path("coming-soon" / "reports")(ComingSoonPage("Reports & Analytics")),
```

with:

```scala
          path("reports")(ReportsPage()),
```

(Leave the other three `coming-soon` entries as-is — only the Reports one is being implemented.)

- [ ] **Step 3: Update the nav link**

In `frontend/src/main/scala/perfectpair/view/layout/LeftNav.scala`, change:

```scala
      NavItem("Reports & Analytics", "/components/coming-soon/reports", Icons.chart),
```

to:

```scala
      NavItem("Reports & Analytics", "/components/reports", Icons.chart),
```

- [ ] **Step 4: Type-check**

Run: `sbt "frontend/compile"`
Expected: `[success]`.

- [ ] **Step 5: Visual verification**

Start the backend and frontend dev servers. Log in, click "Reports & Analytics" in the left nav. Confirm:
the three cards render (match rate horizontal bars, exception doughnut + status pills, aging buckets +
"N overdue" pill) with real data from the QA fixture accounts/disputes seeded earlier this session.
Toggle dark mode — confirm all three charts redraw with light text/grid instead of remaining dark-on-dark
(this is the specific regression class this session hit twice already on other pages). Change the fund
flow filter to a specific fund flow — confirm the charts update to that scope; switch back to "All fund
flows" — confirm they return to the unscoped view.

Stop both dev servers when done.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/ReportsPage.scala frontend/src/main/scala/perfectpair/routing/Routes.scala frontend/src/main/scala/perfectpair/view/layout/LeftNav.scala
git commit -m "Add Reports & Analytics dashboard: match rate, exceptions, aging

Three Chart.js widgets with an optional fund-flow filter, replacing the
'Reports & Analytics' left-nav item's Coming Soon stub. Verified live in
both light and dark mode, including a dark-mode toggle after initial load."
```

---

## Self-Review

**Spec coverage:** Widget definitions and formulas (match rate, exception breakdown, aging buckets +
overdue) — Task 1. `MovementPayload.createdAt` addition — Task 2. Global/fund-flow-scoped endpoint,
`ReportsSummaryPayload` family — Tasks 2–3. Chart.js via slim manual facade (no ScalablyTyped, decided
during brainstorming) — Task 4. `ReportsPage` with fund-flow filter, three widget cards, nav/route wiring
— Task 5. "Future work" seam (`ReportingService.summary` as the swap point for later aggregated-table
reads) — called out in Global Constraints and Task 1's interface note. Error handling (unknown
`fundFlowId` → `NotFoundError`; account with no recon spec → skipped, not thrown) — Task 1's tests, Task
3's smoke test. Out-of-scope trend/resolution-time widgets — untouched by every task.

**Placeholder scan:** No TBD/TODO. Every step has complete, concrete code — including the full 7-test
suite in Task 1 and the full `ReportsPage` body in Task 5.

**Type consistency:** `ReportingService.summary(fundFlowId: Option[ID]): Txn[ReportsSummaryReport]` has
the identical signature in Task 1's tests, implementation, and Task 3's route. `ReportsSummaryReport`'s
three fields (`matchRates`, `exceptions`, `aging`) match `ReportsSummaryPayload`'s fields exactly (Task 1
→ Task 2 → Task 3's conversion function). `ChartCanvas.Series(labels: Seq[String], values: Seq[Double])`
and `ChartCanvas.apply(chartType: String, dataSignal: Signal[Series], horizontal: Boolean = false)` are
defined once in Task 4 and used with that exact shape in all three of Task 5's widget cards.
