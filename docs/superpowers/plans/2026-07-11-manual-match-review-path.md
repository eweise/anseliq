# Manual Match / Review Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user manually force-match unreconciled movements into a `Reconciliation`, undo (void) an existing match, and write off a stuck `InProgress` reconciliation with a recorded reason — closing the gap where the automated `InMemoryReconciler` can't resolve an item and it's stuck forever.

**Architecture:** A new `ManualReconciler` backend service (hexagonal, mirrors `InMemoryReconciler`) exposes three operations via new Cask routes. `Reconciliation` gains a `Voided` status and an `adjustmentReason` field. A new `ManualMatchPage` (Laminar) lets a user check movements on both sides of a `ReconciliationSpec` and match them; `ReconciliationsPage` is extended with Unmatch/Write-Off actions and new columns.

**Tech Stack:** Scala 3, Magnum (SQL/Postgres), Cask (backend/adapter/driving), munit + `rollBackOnly` for backend tests, Scala.js + Laminar + frontroute (frontend).

## Global Constraints

- Backend follows hexagon architecture: domain services depend only on `port.driven` traits, never on adapter implementations (per `backend/CLAUDE.md`).
- No new ZIO code (per `backend/CLAUDE.md`) — this codebase's active reconciliation/routing stack uses Cask + plain Scala, not ZIO.
- Backend tests use munit with `rollBackOnly` for DB isolation (per `backend/CLAUDE.md`).
- No user attribution on manual actions in this iteration — there is no authenticated "current user" wired into the live Cask `App` (see spec `docs/superpowers/specs/2026-07-11-manual-match-review-design.md`).
- No changes to `InMemoryReconciler`'s matching algorithm itself, only the extraction of its `signedAmount` helper.
- No fix to the `movement.reconciliation_id ON DELETE CASCADE` FK — this design never deletes a `Reconciliation` row.

---

## File Structure Overview

**Backend — data model & schema:**
- Modify `backend/db/schema.sql` — add `adjustment_reason` column.
- Modify `backend/src/main/scala/onparity/domain/model/Reconciliation.scala` — add `Voided` status, `adjustmentReason` field.
- Modify `backend/src/main/scala/onparity/domain/model/Movement.scala` — add `signedAmountFor`.
- Modify `backend/src/main/scala/onparity/domain/model/Event.scala` — add 3 new event types.
- Modify `backend/src/main/scala/onparity/domain/service/InMemoryReconciler.scala` — use the new shared `signedAmountFor` instead of its private duplicate.

**Backend — service & routes:**
- Create `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala` — `matchMovements`, `unmatch`, `writeOff`.
- Create `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`.
- Modify `backend/src/main/scala/onparity/adapter/driving/Routes.scala` — 3 new endpoints, wire `manualReconciler`.
- Modify `backend/src/main/scala/onparity/adapter/driving/Payload.scala` — update `reconFromDomain`.
- Modify `backend/src/main/scala/onparity/app/Main.scala` — wire `given manualReconciler`.

**Common (shared DTOs):**
- Modify `common/src/main/scala/onparity/common/http/http.scala` — add `HttpPath.reconciliation`.
- Modify `common/src/main/scala/onparity/common/model/Payload.scala` — `ManualMatchRequest`, `WriteOffRequest`, extend `ReconciliationPayload`.

**Frontend:**
- Create `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala`.
- Modify `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala` — new columns + actions + nav button.
- Modify `frontend/src/main/scala/onparity/routing/Nav.scala` — `toManualMatch`.
- Modify `frontend/src/main/scala/onparity/routing/Routes.scala` — register the new route.

---

### Task 1: `Reconciliation` data model — `Voided` status and `adjustmentReason`

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/model/Reconciliation.scala`
- Modify: `backend/db/schema.sql:59-69`

**Interfaces:**
- Produces: `ReconciliationStatus.Voided` (new enum case), `Reconciliation.adjustmentReason: Option[String]` (new field) — used by Tasks 6–8.

This is a pure data-shape change with no branching logic of its own, so it's verified by compiling and by round-tripping through the DB in Task 6–8's tests (which insert/read a `Reconciliation` with the new field and status) rather than a dedicated unit test here.

- [ ] **Step 1: Add `Voided` to `ReconciliationStatus` and `adjustmentReason` to `Reconciliation`**

Edit `backend/src/main/scala/onparity/domain/model/Reconciliation.scala`:

```scala
package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.{ID, Money}
import onparity.domain.model.ReconciliationStatus.Final

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  case InProgress, Final, Voided

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:        ID                     = createID(),
  specId:        ID,
  appliedRules:  String                 = "",
  description:   String                 = "",
  var status:    ReconciliationStatus   = Final,
  var balance:   Money                  = 0,
  var adjustmentReason: Option[String]  = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Reconciliation:
  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
```

- [ ] **Step 2: Add the column to the schema**

Edit `backend/db/schema.sql`, the `recon.reconciliation` table (around line 59-69), adding `adjustment_reason text` after `balance`:

```sql
create table recon.reconciliation
(
    id                uuid primary key,
    spec_id           uuid                                  not null REFERENCES recon.reconciliation_spec (id),
    description       text,
    applied_rules     text,
    balance           numeric(15, 2)                        not null default 0,
    status            text                                  not null,
    adjustment_reason text,
    created_at        timestamptz default CURRENT_TIMESTAMP not null,
    updated_at        timestamptz default CURRENT_TIMESTAMP not null
);
```

- [ ] **Step 3: Apply the column to the running local test database**

`schema.sql` is a from-scratch bootstrap script (it starts with `drop database if exists recon_db`), so re-running it against a database with existing data would be destructive. Apply the additive change directly instead. From the repo root, run (adjust host/port/credentials if your local Postgres differs from `test-application.conf`):

```bash
docker run --rm postgres:17 psql "postgresql://postgres:postgres@host.docker.internal:5432/recon_db" \
  -c "ALTER TABLE recon.reconciliation ADD COLUMN IF NOT EXISTS adjustment_reason text;"
```

Expected: `ALTER TABLE` printed, no error. If you're bootstrapping a brand new database instead, just run the updated `schema.sql` and skip this step.

- [ ] **Step 4: Compile**

```bash
sbt backend/compile
```

Expected: `[success]`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/onparity/domain/model/Reconciliation.scala backend/db/schema.sql
git commit -m "Add Voided status and adjustmentReason to Reconciliation"
```

---

### Task 2: New domain events for manual actions

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/model/Event.scala`

**Interfaces:**
- Produces: `ReconciliationManuallyMatched`, `ReconciliationVoided`, `ReconciliationWrittenOff` (all extend `Event`) — used by Task 6–8's `ManualReconciler`.

- [ ] **Step 1: Add the three event case classes**

Edit `backend/src/main/scala/onparity/domain/model/Event.scala`:

```scala
package onparity.domain.model

import onparity.common.model.model.ID
import upickle.ReadWriter

import java.util.UUID

sealed trait Event derives ReadWriter {
  val id: UUID
  val eventType: String = this.getClass.getSimpleName
}

case class MoneyMovementsIngested(id: ID = UUID.randomUUID(), movementSpecID: ID) extends Event

case class AccountReconciled(id: ID = UUID.randomUUID(), accountID: ID) extends Event

case class NewFileDetected(id: ID = UUID.randomUUID(), fileInventoryID: ID) extends Event

case class ReconciliationManuallyMatched(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event

case class ReconciliationVoided(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event

case class ReconciliationWrittenOff(id: ID = UUID.randomUUID(), reconciliationID: ID, reason: String) extends Event
```

- [ ] **Step 2: Compile**

```bash
sbt backend/compile
```

Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/scala/onparity/domain/model/Event.scala
git commit -m "Add manual-reconciliation domain events"
```

---

### Task 3: Extract `signedAmountFor` onto `Movement`, refactor `InMemoryReconciler`

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/model/Movement.scala`
- Modify: `backend/src/main/scala/onparity/domain/service/InMemoryReconciler.scala:95,139,143,176,189,207-208`

**Interfaces:**
- Produces: `Movement.signedAmountFor(accountID: ID): Money` — used by `InMemoryReconciler` (this task) and `ManualReconciler` (Task 6-8).

This is a pure refactor (no behavior change), so it's verified by keeping the existing `InmemoryReconcilerSuite` green rather than writing a new test.

- [ ] **Step 1: Add `signedAmountFor` to `Movement`**

Edit `backend/src/main/scala/onparity/domain/model/Movement.scala`:

```scala
package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.{ID, Money}
import onparity.domain.model.helper.JSONBProps

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id:               ID                     = createID(),
  referenceId:          ID                     = createID(), // serves as the idempotency id //todo remove default
  outAccountId:         ID,
  outAccountBalance:    Money                  = 0,
  inAccountId:          ID,
  inAccountBalance:     Money                  = 0,
  amount:               Money,
  properties:           JSONBProps             = JSONBProps(Map()),
  movementSpecId:       ID,
  var reconciliationId: Option[ID]             = None,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec:

  def props = this.properties.props

  def signedAmountFor(accountID: ID): Money =
    if inAccountId == accountID then amount else -amount

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]
```

- [ ] **Step 2: Remove the private duplicate and update call sites in `InMemoryReconciler`**

In `backend/src/main/scala/onparity/domain/service/InMemoryReconciler.scala`:

Delete the private method (currently lines 207-208):
```scala
  private def signedAmount(movement: Movement, accountID: ID): Money =
    if movement.inAccountId == accountID then movement.amount else -movement.amount
```

Replace each of its 5 call sites:
- Line 95: `reconciledDelta = reconciledDelta + signedAmount(outMov, accountID)` → `reconciledDelta = reconciledDelta + outMov.signedAmountFor(accountID)`
- Line 139: `reconciledDelta = reconciledDelta + signedAmount(inMovement, accountID)` → `reconciledDelta = reconciledDelta + inMovement.signedAmountFor(accountID)`
- Line 143: `reconciledDelta = reconciledDelta + signedAmount(out, accountID)` → `reconciledDelta = reconciledDelta + out.signedAmountFor(accountID)`
- Line 176: `reconciledDelta = reconciledDelta + signedAmount(inMovement, accountID)` → `reconciledDelta = reconciledDelta + inMovement.signedAmountFor(accountID)`
- Line 189: `reconciledDelta = reconciledDelta + signedAmount(outMovement, accountID)` → `reconciledDelta = reconciledDelta + outMovement.signedAmountFor(accountID)`

- [ ] **Step 3: Run the existing reconciler suite to confirm no regression**

```bash
sbt "backend/testOnly onparity.domain.service.InmemoryReconcilerSuite"
```

Expected: all tests pass (same as before the refactor).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/scala/onparity/domain/model/Movement.scala backend/src/main/scala/onparity/domain/service/InMemoryReconciler.scala
git commit -m "Extract signedAmountFor onto Movement, share it with InMemoryReconciler"
```

---

### Task 4: Shared DTOs — `ManualMatchRequest`, `WriteOffRequest`, extend `ReconciliationPayload`

**Files:**
- Modify: `common/src/main/scala/onparity/common/http/http.scala:7-22`
- Modify: `common/src/main/scala/onparity/common/model/Payload.scala:106-114`

**Interfaces:**
- Produces: `ManualMatchRequest(inMovementIds, outMovementIds, description)`, `WriteOffRequest(reason)`, extended `ReconciliationPayload(..., balance: Money, adjustmentReason: Option[String])` — used by Task 5 (mapping), Task 9 (routes), Task 10-11 (frontend).

- [ ] **Step 1: Add `HttpPath.reconciliation`**

Edit `common/src/main/scala/onparity/common/http/http.scala`, in `object HttpPath`:

```scala
object HttpPath:
  val prefix          = "/api/v1"
  val fundFlows       = s"$prefix/fund-flows"
  val fundFlow        = s"$prefix/fund-flow"
  val accounts        = s"$prefix/accounts"
  val account         = s"$prefix/account"
  val movementSpecs   = s"$prefix/movement-specs"
  val movementSpec    = s"$prefix/movement-spec"
  val movements       = s"$prefix/movements"
  val reconSpecs      = s"$prefix/recon-specs"
  val reconSpec       = s"$prefix/recon-spec"
  val reconciliations = s"$prefix/reconciliations"
  val reconciliation  = s"$prefix/reconciliation"
  val jobs            = s"$prefix/jobs"
  val events          = s"$prefix/events"

  def url(path: String) = s"http://localhost:8080$path"
```

- [ ] **Step 2: Add request DTOs and extend `ReconciliationPayload`**

Edit `common/src/main/scala/onparity/common/model/Payload.scala`, replacing the existing `ReconciliationPayload` (currently lines 106-114):

```scala
case class ReconciliationPayload(
  id:               String,
  reconSpecId:      String,
  appliedRules:     String,
  description:      String,
  status:           String,
  balance:          Money,
  adjustmentReason: Option[String],
  var createdAt:    OffsetDateTime,
  var updatedAt:    OffsetDateTime
) derives ReadWriter

case class ManualMatchRequest(
  inMovementIds:  Seq[String],
  outMovementIds: Seq[String],
  description:    String = "Manual match"
) derives ReadWriter

case class WriteOffRequest(reason: String) derives ReadWriter
```

- [ ] **Step 3: Compile**

```bash
sbt common.jvm/compile
```

Expected: `[success]`.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/scala/onparity/common/http/http.scala common/src/main/scala/onparity/common/model/Payload.scala
git commit -m "Add ManualMatchRequest/WriteOffRequest DTOs, extend ReconciliationPayload"
```

---

### Task 5: Update `reconFromDomain` mapping

**Files:**
- Modify: `backend/src/main/scala/onparity/adapter/driving/Payload.scala:92-101`

**Interfaces:**
- Consumes: `Reconciliation.balance`, `Reconciliation.adjustmentReason` (Task 1), `ReconciliationPayload` (Task 4).
- Produces: `Payload.reconFromDomain(recon: Reconciliation): ReconciliationPayload` populated with the two new fields — used by Task 9's routes.

- [ ] **Step 1: Populate the new fields**

Edit `backend/src/main/scala/onparity/adapter/driving/Payload.scala`, replacing `reconFromDomain` (currently lines 92-101):

```scala
  def reconFromDomain(recon: Reconciliation): ReconciliationPayload =
    ReconciliationPayload(
      id               = recon.id.toString,
      reconSpecId      = recon.specId.toString,
      appliedRules     = recon.appliedRules,
      description      = recon.description,
      status           = recon.status.toString,
      balance          = recon.balance,
      adjustmentReason = recon.adjustmentReason,
      createdAt        = recon.createdAt.get,
      updatedAt        = recon.updatedAt.get
    )
```

- [ ] **Step 2: Compile**

```bash
sbt backend/compile
```

Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/scala/onparity/adapter/driving/Payload.scala
git commit -m "Populate balance and adjustmentReason in reconFromDomain"
```

---

### Task 6: `ManualReconciler.matchMovements`

**Files:**
- Create: `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala`
- Create: `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`

**Interfaces:**
- Consumes: `Movement.signedAmountFor` (Task 3), `Reconciliation` with `Voided`/`adjustmentReason` (Task 1), `ReconciliationManuallyMatched` event (Task 2), `MovementRepo.findAllById` (existing, from `DomainRepo`), `MovementRepo.findByReconciliationID` (existing).
- Produces: `ManualReconciler.matchMovements(reconSpecId: ID, inMovementIds: Seq[ID], outMovementIds: Seq[ID], description: String): Txn[Reconciliation]` — used by Task 9's route. Constructor: `ManualReconciler(using accountRepo: AccountRepo, movementRepo: MovementRepo, reconRepo: ReconciliationRepo, reconSpecRepo: ReconciliationSpecRepo, eventStore: EventStore)`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`:

```scala
package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, EventRepoLive}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{IllegalArgumentError, IllegalStateError, Movement, MovementSpec, ReconciliationStatus}
import onparity.testing.rollBackOnly

import javax.sql.DataSource

class ManualReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given clockSvc:   ClockService = ClockServiceLive()
  given eventRepo:  EventRepoLive()
  given eventStore: EventStore = EventStore()

  def createMovement(movementSpec: MovementSpec, amount: BigDecimal)(using data: DataSetup): Movement =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  test("matchMovements with equal totals creates a Final reconciliation and links both movements"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement.id),
          "test match"
        )

        assertEquals(recon.status, ReconciliationStatus.Final)
        assertEquals(recon.balance, BigDecimal(0))

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, Some(recon.id))
        assertEquals(updatedOut.reconciliationId, Some(recon.id))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(0))     // +100 (in) -100 (out) = 0
        assertEquals(account.unreconciledBalance, BigDecimal(0))

  test("matchMovements with unequal totals creates an InProgress reconciliation with the residual balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement   = createMovement(data.journalToRecon, 300)
        val outMovement1 = createMovement(data.reportToRecon, 100)
        val outMovement2 = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement1.id, outMovement2.id),
          "test partial match"
        )

        assertEquals(recon.status, ReconciliationStatus.InProgress)
        assertEquals(recon.balance, BigDecimal(100))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(100))   // +300 (in) -100 -100 (out) = 100
        assertEquals(account.unreconciledBalance, BigDecimal(-100))

  test("matchMovements rejects a movement that is already reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "first match")

        val anotherOut = createMovement(data.reportToRecon, 100)
        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(anotherOut.id), "second match")

  test("matchMovements rejects a movement from the wrong movement spec"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val wrongSpecMovement = createMovement(data.reportToRecon, 100) // belongs to outMovementSpecId, passed as "in"
        val outMovement       = createMovement(data.reportToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(wrongSpecMovement.id), Seq(outMovement.id), "bad spec")

  test("matchMovements rejects an empty selection on either side"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement = createMovement(data.journalToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(), "no out movements")
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: compile error — `ManualReconciler` does not exist. (If it somehow compiles, the tests still fail with "not found: value ManualReconciler" — either way, confirms the code doesn't exist yet.)

- [ ] **Step 3: Write `ManualReconciler` with `matchMovements`**

Create `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala`:

```scala
package onparity.domain.service

import onparity.common.model.model.{ID, Money, Zero}
import onparity.domain.model.*
import onparity.domain.port.driven.*

class ManualReconciler(using
  val accountRepo:   AccountRepo,
  val movementRepo:  MovementRepo,
  val reconRepo:      ReconciliationRepo,
  val reconSpecRepo: ReconciliationSpecRepo,
  val eventStore:    EventStore
):

  def matchMovements(
    reconSpecId:    ID,
    inMovementIds:  Seq[ID],
    outMovementIds: Seq[ID],
    description:    String
  ): Txn[Reconciliation] =
    if inMovementIds.isEmpty then throw IllegalArgumentError("inMovementIds", "must select at least one in-movement")
    if outMovementIds.isEmpty then throw IllegalArgumentError("outMovementIds", "must select at least one out-movement")

    val reconSpec = reconSpecRepo.findById(reconSpecId).ErrIfNotFound("reconciliation spec", reconSpecId)

    val inMovements  = movementRepo.findAllById(inMovementIds)
    val outMovements = movementRepo.findAllById(outMovementIds)

    if inMovements.size != inMovementIds.size then
      throw IllegalArgumentError("inMovementIds", "one or more in-movements were not found")
    if outMovements.size != outMovementIds.size then
      throw IllegalArgumentError("outMovementIds", "one or more out-movements were not found")

    for m <- inMovements do
      if m.movementSpecId != reconSpec.inMovementSpecId then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} does not belong to this spec's in-movement-spec")
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} is already reconciled")

    for m <- outMovements do
      if m.movementSpecId != reconSpec.outMovementSpecId then
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} does not belong to this spec's out-movement-spec")
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} is already reconciled")

    val balance = inMovements.map(_.amount).sum - outMovements.map(_.amount).sum
    val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

    val reconciliation = Reconciliation(
      specId       = reconSpecId,
      appliedRules = "manual",
      description  = description,
      status       = status,
      balance      = balance
    )
    reconRepo.insert(reconciliation)

    var reconciledDelta: Money = Zero
    for m <- inMovements ++ outMovements do
      m.reconciliationId = Some(reconciliation.id)
      movementRepo.update(m)
      reconciledDelta = reconciledDelta + m.signedAmountFor(reconSpec.accountId)

    val account = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
    account.reconciledBalance   = account.reconciledBalance + reconciledDelta
    account.unreconciledBalance = account.unreconciledBalance - reconciledDelta
    accountRepo.update(account)

    eventStore.publishEvent(ReconciliationManuallyMatched(reconciliationID = reconciliation.id))

    reconciliation
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/onparity/domain/service/ManualReconciler.scala backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala
git commit -m "Add ManualReconciler.matchMovements"
```

---

### Task 7: `ManualReconciler.unmatch`

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala`
- Modify: `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`

**Interfaces:**
- Consumes: same as Task 6, plus `ReconciliationVoided` event (Task 2).
- Produces: `ManualReconciler.unmatch(reconciliationId: ID): Txn[Unit]` — used by Task 9's route.

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`:

```scala
  test("unmatch reverses account balances and returns movements to unreconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 60)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "to undo")

        val beforeUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(beforeUnmatch.reconciledBalance, BigDecimal(40)) // +100 -60

        manualReconciler.unmatch(recon.id)

        val voided = data.reconRepo.findById(recon.id).get
        assertEquals(voided.status, ReconciliationStatus.Voided)

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, None)
        assertEquals(updatedOut.reconciliationId, None)

        val afterUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterUnmatch.reconciledBalance, BigDecimal(0))
        assertEquals(afterUnmatch.unreconciledBalance, BigDecimal(0))

  test("unmatch on an already-voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "double undo")

        manualReconciler.unmatch(recon.id)
        intercept[IllegalStateError]:
          manualReconciler.unmatch(recon.id)
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: the two new tests fail with "value unmatch is not a member of ManualReconciler".

- [ ] **Step 3: Implement `unmatch`**

Add to `ManualReconciler` in `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala` (after `matchMovements`):

```scala
  def unmatch(reconciliationId: ID): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status == ReconciliationStatus.Voided then
      throw IllegalStateError(s"reconciliation $reconciliationId is already voided")

    val reconSpec = reconSpecRepo.findById(reconciliation.specId).ErrIfNotFound("reconciliation spec", reconciliation.specId)
    val movements  = movementRepo.findByReconciliationID(reconciliationId)

    var reconciledDelta: Money = Zero
    for m <- movements do
      reconciledDelta = reconciledDelta + m.signedAmountFor(reconSpec.accountId)
      m.reconciliationId = None
      movementRepo.update(m)

    val account = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
    account.reconciledBalance   = account.reconciledBalance - reconciledDelta
    account.unreconciledBalance = account.unreconciledBalance + reconciledDelta
    accountRepo.update(account)

    reconciliation.status = ReconciliationStatus.Voided
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationVoided(reconciliationID = reconciliationId))
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/onparity/domain/service/ManualReconciler.scala backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala
git commit -m "Add ManualReconciler.unmatch"
```

---

### Task 8: `ManualReconciler.writeOff`

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala`
- Modify: `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`

**Interfaces:**
- Consumes: same as Task 6/7, plus `ReconciliationWrittenOff` event (Task 2), `InMemoryReconciler` (existing, for the regression test).
- Produces: `ManualReconciler.writeOff(reconciliationId: ID, reason: String): Txn[Unit]` — used by Task 9's route.

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala`:

```scala
  test("writeOff closes an InProgress reconciliation with a reason and leaves account balances untouched"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "residual match")
        assertEquals(recon.status, ReconciliationStatus.InProgress)

        val beforeWriteOff = data.accountRepo.findById(data.reconAccount.id).get

        manualReconciler.writeOff(recon.id, "bank fee")

        val closed = data.reconRepo.findById(recon.id).get
        assertEquals(closed.status, ReconciliationStatus.Final)
        assertEquals(closed.adjustmentReason, Some("bank fee"))
        assertEquals(closed.balance, BigDecimal(10)) // residual preserved as the historical write-off amount

        val afterWriteOff = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterWriteOff.reconciledBalance, beforeWriteOff.reconciledBalance)
        assertEquals(afterWriteOff.unreconciledBalance, beforeWriteOff.unreconciledBalance)

  test("writeOff on a Final reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "clean match")
        assertEquals(recon.status, ReconciliationStatus.Final)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("writeOff on a Voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "voided match")
        manualReconciler.unmatch(recon.id)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("after writeOff, InMemoryReconciler no longer tries to top up the reconciliation"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler   = ManualReconciler()
        val automaticReconciler = InMemoryReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "will write off")
        manualReconciler.writeOff(recon.id, "bank fee")

        // A fresh unmatched out-movement that would otherwise top up this reconciliation's residual
        createMovement(data.reportToRecon, 10)

        automaticReconciler.reconcileAccount(data.reconAccount.id)

        val stillClosed = data.reconRepo.findById(recon.id).get
        assertEquals(stillClosed.status, ReconciliationStatus.Final)
        assertEquals(stillClosed.balance, BigDecimal(10))
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: the 4 new tests fail with "value writeOff is not a member of ManualReconciler".

- [ ] **Step 3: Implement `writeOff`**

Add to `ManualReconciler` in `backend/src/main/scala/onparity/domain/service/ManualReconciler.scala` (after `unmatch`):

```scala
  def writeOff(reconciliationId: ID, reason: String): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status != ReconciliationStatus.InProgress then
      throw IllegalStateError(s"reconciliation $reconciliationId is not InProgress")

    reconciliation.adjustmentReason = Some(reason)
    reconciliation.status = ReconciliationStatus.Final
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationWrittenOff(reconciliationID = reconciliationId, reason = reason))
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
sbt "backend/testOnly onparity.domain.service.ManualReconcilerSuite"
```

Expected: all 11 tests pass.

- [ ] **Step 5: Run the full backend suite to check for regressions**

```bash
sbt backend/test
```

Expected: same pass/fail counts as before this feature (pre-existing `CreateFileInventoryTrackerS3Suite` LocalStack failures are unrelated and expected to still fail; everything else passes).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/onparity/domain/service/ManualReconciler.scala backend/src/test/scala/onparity/domain/service/ManualReconcilerSuite.scala
git commit -m "Add ManualReconciler.writeOff"
```

---

### Task 9: Wire `ManualReconciler` into `Main.scala` and add REST endpoints

**Files:**
- Modify: `backend/src/main/scala/onparity/app/Main.scala:36-41`
- Modify: `backend/src/main/scala/onparity/adapter/driving/Routes.scala`

**Interfaces:**
- Consumes: `ManualReconciler` (Task 6-8), `ManualMatchRequest`/`WriteOffRequest`/`HttpPath.reconciliation` (Task 4), `Payload.reconFromDomain` (Task 5).
- Produces: `POST /api/v1/recon-spec/:id/manual-match`, `POST /api/v1/reconciliation/:id/unmatch`, `POST /api/v1/reconciliation/:id/write-off` — used by Task 10-11's frontend pages.

- [ ] **Step 1: Wire the service in `Main.scala`**

Edit `backend/src/main/scala/onparity/app/Main.scala`, adding after the existing `given reconciler: InMemoryReconciler = InMemoryReconciler()` (line 41):

```scala
    given reconciler:       InMemoryReconciler = InMemoryReconciler()
    given manualReconciler: ManualReconciler    = ManualReconciler()
```

- [ ] **Step 2: Add `manualReconciler` to `App`'s constructor params**

Edit `backend/src/main/scala/onparity/adapter/driving/Routes.scala`, in `class App(using ...)`, adding after `reconciler: InMemoryReconciler`:

```scala
class App(using
  dataSource:         DataSource,
  accountRepo:        AccountRepo,
  reconRepo:          ReconciliationRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
  movementSpecRepo:   MovementSpecRepo,
  movementRepo:       MovementRepo,
  fundFlowOperations: FundFlowOperations,
  jobManager:         JobManager,
  ingestor:           Ingestor,
  reconciler:         InMemoryReconciler,
  manualReconciler:   ManualReconciler,
  jobRepo:            JobRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  eventRepo:          EventRepo
) extends cask.MainRoutes:
```

- [ ] **Step 3: Add the three routes**

Edit `backend/src/main/scala/onparity/adapter/driving/Routes.scala`, adding after the existing `reoncileAccount` route:

```scala
  @cask.route(s"${HttpPath.reconSpec}/:id/manual-match", methods = HttpCmd.POST)
  def manualMatch(id: String, request: cask.Request): String =
    val payload = read[ManualMatchRequest](request.text())
    transact(transactor):
      val reconciliation = manualReconciler.matchMovements(
        UUID.fromString(id),
        payload.inMovementIds.map(UUID.fromString),
        payload.outMovementIds.map(UUID.fromString),
        payload.description
      )
      write(Payload.reconFromDomain(reconciliation))

  @cask.route(s"${HttpPath.reconciliation}/:id/unmatch", methods = HttpCmd.POST)
  def unmatchReconciliation(id: String): String =
    transact(transactor):
      manualReconciler.unmatch(UUID.fromString(id))
    write("voided")

  @cask.route(s"${HttpPath.reconciliation}/:id/write-off", methods = HttpCmd.POST)
  def writeOffReconciliation(id: String, request: cask.Request): String =
    val payload = read[WriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.writeOff(UUID.fromString(id), payload.reason)
    write("written-off")
```

- [ ] **Step 4: Compile**

```bash
sbt backend/compile
```

Expected: `[success]`.

- [ ] **Step 5: Manual smoke test against a running backend**

Start the backend (adjust to however it's normally started in this repo, e.g. `sbt backend/run`), then from another terminal:

```bash
curl -s -X POST http://localhost:8080/api/v1/recon-spec/<a-real-recon-spec-id>/manual-match \
  -H 'Content-Type: application/json' \
  -d '{"inMovementIds":[],"outMovementIds":[],"description":"smoke test"}'
```

Expected: a 4xx/error response body mentioning the empty selection (not a 404 for the route itself, not a 500 stack trace) — confirms the route is registered and reaches `ManualReconciler`'s validation.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/onparity/app/Main.scala backend/src/main/scala/onparity/adapter/driving/Routes.scala
git commit -m "Wire ManualReconciler and add manual-match/unmatch/write-off endpoints"
```

---

### Task 10: `ManualMatchPage` (frontend)

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Nav.scala:40-41`
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala:25`

**Interfaces:**
- Consumes: `AppState.editReconSpec: Var[Option[ReconciliationSpecPayload]]` (existing), `GET /movements/:movementSpecID/unreconciled` (existing), `POST /recon-spec/:id/manual-match` (Task 9), `ManualMatchRequest`/`ReconciliationPayload` (Task 4), `Table.create`, `Buttons.linkButton`/`textButton` (existing), `ApiClient.get`/`post` (existing).
- Produces: `ManualMatchPage.apply(accountId: String): HtmlElement`, `Nav.toManualMatch(accountId: String): Unit` — used by Task 11's "Manual Match" button.

- [ ] **Step 1: Add `Nav.toManualMatch`**

Edit `frontend/src/main/scala/onparity/routing/Nav.scala`, adding after `toReconciliations`:

```scala
  def toReconciliations(accountId: String): Unit =
    push(s"/components/accounts/$accountId/reconciliations")

  def toManualMatch(accountId: String): Unit =
    push(s"/components/accounts/$accountId/manual-match")
```

- [ ] **Step 2: Register the route**

Edit `frontend/src/main/scala/onparity/routing/Routes.scala`, adding inside `firstMatch(...)` after the `reconciliations` route:

```scala
          path("accounts" / segment / "reconciliations")(accountId => ReconciliationsPage(accountId)),
          path("accounts" / segment / "manual-match")(accountId => ManualMatchPage(accountId)),
```

- [ ] **Step 3: Write `ManualMatchPage`**

Create `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala`:

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{ManualMatchRequest, MovementPayload, ReconciliationPayload, ReconciliationSpecPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Table}
import upickle.default.*

object ManualMatchPage:

  case class State(
    inMovements:  Seq[MovementPayload] = Seq.empty,
    outMovements: Seq[MovementPayload] = Seq.empty,
    selectedIn:   Set[String]          = Set.empty,
    selectedOut:  Set[String]          = Set.empty,
    description:  String               = "Manual match",
    message:      Option[String]       = None
  )

  def apply(accountId: String): HtmlElement =
    val spec   = AppState.editReconSpec.now().getOrElse(ReconciliationSpecPayload())
    val specId = spec.id.getOrElse("")
    val state  = Var(State())
    val loadBus  = EventBus[Unit]()
    val matchBus = EventBus[Unit]()

    def loadMovements() =
      ApiClient.get[Seq[MovementPayload]](
        HttpPath.url(s"${HttpPath.movements}/${spec.inMovementSpecId}/unreconciled"),
        SessionStore.token
      ).combineWith(
        ApiClient.get[Seq[MovementPayload]](
          HttpPath.url(s"${HttpPath.movements}/${spec.outMovementSpecId}/unreconciled"),
          SessionStore.token
        )
      )

    def toggle(selected: Set[String], id: String): Set[String] =
      if selected.contains(id) then selected - id else selected + id

    div(
      onMountCallback { _ => loadBus.emit(()) },
      loadBus.events.flatMapSwitch(_ => loadMovements()) --> {
        case (Right(ins), Right(outs)) =>
          state.update(_.copy(inMovements = ins, outMovements = outs, selectedIn = Set.empty, selectedOut = Set.empty))
        case (Left(e), _) => AppState.globalError.set(Some(e))
        case (_, Left(e)) => AppState.globalError.set(Some(e))
      },
      matchBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.post[ReconciliationPayload, ManualMatchRequest](
          HttpPath.url(s"${HttpPath.reconSpec}/$specId/manual-match"),
          ManualMatchRequest(
            inMovementIds  = s.selectedIn.toSeq,
            outMovementIds = s.selectedOut.toSeq,
            description    = s.description
          ),
          SessionStore.token
        )
      } --> {
        case Right(recon) =>
          state.update(_.copy(message = Some(s"Match saved as ${recon.status}")))
          loadBus.emit(())
        case Left(e) => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-4xl",
        h2(cls := "text-2xl font-medium mb-6", s"${spec.name} — Manual Match"),
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.toReconciliations(accountId))),
        div(
          cls := "grid grid-cols-2 gap-4 mt-6 text-left",
          div(
            h3(cls := "font-medium mb-2", "In Movements"),
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
                    span(m.referenceId),
                    span(m.amount.toString)
                  )
                )
              )
            }
          ),
          div(
            h3(cls := "font-medium mb-2", "Out Movements"),
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
                    span(m.referenceId),
                    span(m.amount.toString)
                  )
                )
              )
            }
          )
        ),
        div(
          cls := "mt-4 text-sm",
          child.text <-- state.signal.map { s =>
            val inTotal  = s.inMovements.filter(m => s.selectedIn.contains(m.id)).map(_.amount).sum
            val outTotal = s.outMovements.filter(m => s.selectedOut.contains(m.id)).map(_.amount).sum
            val diff     = inTotal - outTotal
            val note     = if diff == BigDecimal(0) then "will close as Final" else "will save as InProgress"
            s"Selected In: ${s.selectedIn.size} ($inTotal)  Selected Out: ${s.selectedOut.size} ($outTotal)  Diff: $diff — $note"
          }
        ),
        div(
          cls := "mt-4",
          child.maybe <-- state.signal.map(_.message.map(m => span(cls := "text-sm", m)))
        ),
        div(
          cls := "mt-4",
          button(
            cls := "bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium disabled:opacity-40",
            typ := "button",
            disabled <-- state.signal.map(s => s.selectedIn.isEmpty || s.selectedOut.isEmpty),
            onClick.mapToUnit --> Observer[Unit](_ => matchBus.emit(())),
            "Match Selected"
          )
        )
      )
    )
```

- [ ] **Step 4: Compile the frontend**

```bash
sbt "frontend/Compile/fastLinkJS"
```

Expected: `[success]`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala frontend/src/main/scala/onparity/routing/Nav.scala frontend/src/main/scala/onparity/routing/Routes.scala
git commit -m "Add ManualMatchPage for manually matching unreconciled movements"
```

---

### Task 11: Extend `ReconciliationsPage` with Unmatch / Write-Off actions

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala`

**Interfaces:**
- Consumes: extended `ReconciliationPayload` (Task 4), `POST /reconciliation/:id/unmatch`, `POST /reconciliation/:id/write-off` (Task 9), `Nav.toManualMatch` (Task 10).
- Produces: updated `ReconciliationsPage.apply(accountId: String): HtmlElement` with Balance/Adjustment Reason columns and row actions.

- [ ] **Step 1: Rewrite the page**

Replace the full contents of `frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala`:

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{ReconciliationPayload, ReconciliationSpecPayload, WriteOffRequest}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, Table}
import upickle.default.*

object ReconciliationsPage:

  case class State(
    recons:          Seq[ReconciliationPayload] = Seq.empty,
    writeOffReasonBy: Map[String, String]       = Map.empty, // reconciliation id -> in-progress reason text
    editingWriteOff:  Option[String]             = None        // reconciliation id currently showing the reason field
  )

  def apply(accountId: String): HtmlElement =
    val spec   = AppState.editReconSpec.now().getOrElse(ReconciliationSpecPayload())
    val specId = spec.id.getOrElse("")
    val state  = Var(State())
    val loadBus     = EventBus[Unit]()
    val unmatchBus  = EventBus[String]()
    val writeOffBus = EventBus[String]()

    def load() =
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.reconSpec}/$specId/reconciliations"),
        SessionStore.token
      )

    div(
      onMountCallback { _ => loadBus.emit(()) },
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(rs) => state.update(_.copy(recons = rs))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      unmatchBus.events.flatMapSwitch { reconId =>
        ApiClient.postRaw(HttpPath.url(s"${HttpPath.reconciliation}/$reconId/unmatch"), "", SessionStore.token)
      } --> {
        case Right(_) => loadBus.emit(())
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      writeOffBus.events.flatMapSwitch { reconId =>
        val reason = state.now().writeOffReasonBy.getOrElse(reconId, "")
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/$reconId/write-off"),
          write(WriteOffRequest(reason)),
          SessionStore.token
        )
      } --> {
        case Right(_) =>
          state.update(_.copy(editingWriteOff = None))
          loadBus.emit(())
        case Left(e) => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        h2(cls := "text-2xl font-medium mb-6", "Reconciliations"),
        Buttons.linkButton("Back to Recon Specs", Observer[Unit](_ => Nav.toReconSpecs(accountId))),
        Buttons.textButton("Manual Match", Observer[Unit](_ => Nav.toManualMatch(accountId))),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Balance", "Adjustment Reason", "Created At", "Updated At", "Actions"),
            s.recons.map(r =>
              Seq(
                span(r.description),
                span(r.status),
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
            )
          )
        }
      )
    )
```

- [ ] **Step 2: Compile the frontend**

```bash
sbt "frontend/Compile/fastLinkJS"
```

Expected: `[success]`.

- [ ] **Step 3: Manual browser verification**

Run the app per this repo's normal dev workflow. Navigate: Fund Flow → an account with a recon spec → Reconciliations. Confirm:
- New columns "Balance" and "Adjustment Reason" render.
- "Manual Match" button navigates to the new page.
- On the Manual Match page, checking one row on each side enables "Match Selected"; the diff line updates live as selections change; submitting creates a reconciliation and both matched rows disappear from the unreconciled lists.
- Back on Reconciliations, an `InProgress` row shows "Write Off" and "Unmatch"; a `Final` row shows only "Unmatch"; after clicking "Unmatch" the row shows "Voided" with no actions.
- Clicking "Write Off" on an `InProgress` row reveals a reason field; confirming sets status to `Final` and populates "Adjustment Reason".

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ReconciliationsPage.scala
git commit -m "Add Unmatch/Write-Off actions and Manual Match entry point to ReconciliationsPage"
```

---

## Post-Implementation Check

After all 11 tasks are committed, run the full backend suite once more and confirm the frontend still links cleanly:

```bash
sbt backend/test "frontend/Compile/fastLinkJS"
```

Expected: same backend pass/fail counts as the Task 8 checkpoint (pre-existing `CreateFileInventoryTrackerS3Suite` failures only); frontend link succeeds.
