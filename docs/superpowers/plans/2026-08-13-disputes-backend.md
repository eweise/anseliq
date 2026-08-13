# Exceptions & Disputes Backend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `Dispute` entity that flags either an unreconciled `Movement` or an in-progress
`Reconciliation` for investigation, with category/status/resolution tracking, exposed via three new REST
endpoints. Backend only — no frontend changes.

**Architecture:** Follows the existing hexagonal layering exactly: `domain/model` (entity + enums) →
`domain/port/driven` (repo trait) → `adapter/driven` (repo impl) → `domain/service` (business logic,
mirrors `ManualReconciler`) → `adapter/driving` (cask routes + payload conversion) → `common/model` (shared
DTOs consumed by the frontend later). `referenceId: ID` + `targetType: DisputeTarget` is a polymorphic
reference (one column, no native FK) rather than two nullable foreign keys — see the design spec for the
reasoning.

**Tech Stack:** Scala 3, Magnum (SQL/DbCodec), PostgreSQL, cask (HTTP routes), munit + `rollBackOnly` (tests).

## Global Constraints

- Design doc: `docs/superpowers/specs/2026-08-13-disputes-design.md` — every field name, enum case, and
  route shape below is copied from it verbatim.
- No frontend changes. No automatic dispute detection. No assignee field. No status-transition guards beyond
  requiring a non-blank `resolutionNote` to reach `Resolved`. No mutation of the `Movement`/`Reconciliation`
  a `Dispute` targets.
- Enum DB encoding: Magnum's `DbCodec` derivation on a `@Table`-annotated enum applies `CamelToSnakeCase` to
  case names (confirmed precedent: `Relationship.OneToOne` persists as `'one_to_one'`). Do not add manual
  string mapping — the derived codec already handles it.
- The local Postgres `recon_db` is a **shared database** — `backend/src/test/resources/test-application.conf`
  points tests at the same `recon_db` used for manual/dev work (tests are safe because they run inside
  `rollBackOnly` transactions that never commit). Schema changes must be applied to it with an **additive**
  `CREATE TABLE`/`CREATE INDEX` via `psql`, never `make create-db` / a full drop-and-recreate, which would
  destroy real data sitting in that database.
- Verify every task with `sbt backend/compile` (and `sbt common/compile` for the DTO task) — must succeed
  with no errors.

---

### Task 1: Schema + `Dispute` domain model

**Files:**
- Modify: `backend/db/schema.sql`
- Create: `backend/src/main/scala/perfectpair/domain/model/Dispute.scala`

**Interfaces:**
- Produces: `Dispute` case class, `DisputeTarget` enum (`Movement`, `Reconciliation`), `DisputeCategory` enum
  (`AmountMismatch`, `MissingCounterpart`, `Duplicate`, `TimingDifference`, `DataQuality`), `DisputeStatus`
  enum (`New`, `Investigating`, `Escalated`, `Resolved`) — consumed by every later task.

- [ ] **Step 1: Add the `recon.dispute` table to `backend/db/schema.sql`**

Add this block anywhere after the `recon.reconciliation` table definition (it doesn't reference other
tables via foreign key, so placement relative to them doesn't matter):

```sql
create table recon.dispute
(
    id              uuid primary key,
    reference_id    uuid not null,
    target_type     text not null,
    category        text not null,
    detail          text,
    status          text not null default 'new',
    resolution_note text,
    created_at      timestamptz default CURRENT_TIMESTAMP not null,
    updated_at      timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_reference_id ON recon.dispute (reference_id);
CREATE INDEX dispute_status_created_at ON recon.dispute (status, created_at);
```

- [ ] **Step 2: Apply the new table to the local `recon_db` additively**

Run (adjust the `psql` path if different on your machine — matches the path used elsewhere in this repo's
`Makefile`):

```bash
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "
create table recon.dispute
(
    id              uuid primary key,
    reference_id    uuid not null,
    target_type     text not null,
    category        text not null,
    detail          text,
    status          text not null default 'new',
    resolution_note text,
    created_at      timestamptz default CURRENT_TIMESTAMP not null,
    updated_at      timestamptz default CURRENT_TIMESTAMP not null
);"
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "CREATE INDEX dispute_reference_id ON recon.dispute (reference_id);"
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "CREATE INDEX dispute_status_created_at ON recon.dispute (status, created_at);"
```

Expected: three `CREATE TABLE`/`CREATE INDEX` confirmations, no errors. If `recon.dispute` already exists
(re-running this plan), skip this step rather than erroring out the whole table.

- [ ] **Step 3: Create `Dispute.scala`**

```scala
package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeTarget derives DbCodec:
  case Movement, Reconciliation

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeCategory derives DbCodec:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeStatus derives DbCodec:
  case New, Investigating, Escalated, Resolved

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Dispute(
  @Id id:            ID                     = createID(),
  referenceId:       ID,
  targetType:        DisputeTarget,
  category:          DisputeCategory,
  detail:            Option[String]         = None,
  var status:         DisputeStatus          = DisputeStatus.New,
  var resolutionNote: Option[String]         = None,
  var createdAt:      Option[OffsetDateTime] = None,
  var updatedAt:      Option[OffsetDateTime] = None
) derives DbCodec

object Dispute:
  val Table = TableInfo[Dispute, Dispute, UUID]
```

- [ ] **Step 4: Type-check**

Run: `sbt backend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Commit**

```bash
git add backend/db/schema.sql backend/src/main/scala/perfectpair/domain/model/Dispute.scala
git commit -m "Add Dispute domain model and recon.dispute table

Polymorphic reference (referenceId + targetType) rather than two nullable
foreign keys - see docs/superpowers/specs/2026-08-13-disputes-design.md for
the referential-integrity trade-off this makes."
```

---

### Task 2: `DisputeRepo`, `DisputeService`, and tests

**Files:**
- Create: `backend/src/main/scala/perfectpair/domain/port/driven/DisputeRepo.scala`
- Create: `backend/src/main/scala/perfectpair/adapter/driven/DisputeRepoLive.scala`
- Modify: `backend/src/main/scala/perfectpair/domain/model/Event.scala`
- Modify: `backend/src/test/scala/perfectpair/domain/DataSetup.scala`
- Create: `backend/src/test/scala/perfectpair/domain/service/DisputeServiceSuite.scala`
- Create: `backend/src/main/scala/perfectpair/domain/service/DisputeService.scala`

**Interfaces:**
- Consumes: `Dispute`, `DisputeTarget`, `DisputeCategory`, `DisputeStatus` from Task 1; existing
  `MovementRepo`, `ReconciliationRepo`, `EventStore`, `ClockService`, `ErrIfNotFound` extension,
  `IllegalArgumentError`.
- Produces: `DisputeRepo` trait, `DisputeService.raise(referenceId, targetType, category, detail):
  Txn[Dispute]` and `DisputeService.updateStatus(disputeId, newStatus, resolutionNote): Txn[Dispute]` —
  consumed by Task 3's routes.

- [ ] **Step 1: Create the repo port — no custom methods needed**

```scala
package perfectpair.domain.port.driven

import perfectpair.domain.model.Dispute

trait DisputeRepo extends DomainRepo[Dispute, Dispute]
```

- [ ] **Step 2: Create the repo adapter**

```scala
package perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import perfectpair.domain.model.Dispute
import perfectpair.domain.port.driven.DisputeRepo

import java.util.UUID

class DisputeRepoLive extends Repo[Dispute, Dispute, UUID] with DisputeRepo
```

- [ ] **Step 3: Add the two new events to `Event.scala`**

Add these two lines after the existing `ReconciliationWrittenOff` case class:

```scala
case class DisputeRaised(id: ID = UUID.randomUUID(), disputeID: ID) extends Event

case class DisputeStatusChanged(id: ID = UUID.randomUUID(), disputeID: ID, newStatus: String) extends Event
```

- [ ] **Step 4: Wire `disputeRepo` into the shared test fixture**

In `backend/src/test/scala/perfectpair/domain/DataSetup.scala`, add one line among the existing `given`
declarations (e.g. right after `given reconRepo: ReconciliationRepo = ReconciliationRepoLive()`):

```scala
  given disputeRepo:        DisputeRepo            = DisputeRepoLive()
```

- [ ] **Step 5: Write the failing tests**

```scala
package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, EventRepoLive, JobRepoLive}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{DisputeCategory, DisputeStatus, DisputeTarget, IllegalArgumentError, Movement, MovementSpec, NotFoundError}
import perfectpair.domain.port.driven.{JobRepo, Txn}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class DisputeServiceSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given clockSvc:   ClockService = ClockServiceLive()
  given eventRepo:  EventRepoLive()
  given eventStore: EventStore = EventStore()
  given jobRepo:    JobRepo = JobRepoLive()

  def createMovement(movementSpec: MovementSpec, amount: BigDecimal)(using data: DataSetup): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  test("raise on a Movement creates a New dispute"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)

        val dispute = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.MissingCounterpart, Some("no match found"))

        assertEquals(dispute.referenceId, movement.id)
        assertEquals(dispute.targetType, DisputeTarget.Movement)
        assertEquals(dispute.category, DisputeCategory.MissingCounterpart)
        assertEquals(dispute.detail, Some("no match found"))
        assertEquals(dispute.status, DisputeStatus.New)
        assertEquals(dispute.resolutionNote, None)
        assert(dispute.createdAt.isDefined, "createdAt must be set on the returned dispute, not just the persisted row")

        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.referenceId, movement.id)

  test("raise on a Reconciliation creates a New dispute"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()
        val disputeService    = DisputeService()

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "partial match")

        val dispute = disputeService.raise(recon.id, DisputeTarget.Reconciliation, DisputeCategory.AmountMismatch, None)

        assertEquals(dispute.referenceId, recon.id)
        assertEquals(dispute.targetType, DisputeTarget.Reconciliation)
        assertEquals(dispute.category, DisputeCategory.AmountMismatch)
        assertEquals(dispute.detail, None)

  test("raise on a nonexistent Movement errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        intercept[NotFoundError]:
          disputeService.raise(java.util.UUID.randomUUID(), DisputeTarget.Movement, DisputeCategory.DataQuality, None)

  test("raise on a nonexistent Reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        intercept[NotFoundError]:
          disputeService.raise(java.util.UUID.randomUUID(), DisputeTarget.Reconciliation, DisputeCategory.TimingDifference, None)

  test("updateStatus to Resolved without a resolutionNote errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.updateStatus(dispute.id, DisputeStatus.Resolved, None)

  test("updateStatus to Resolved with a resolutionNote succeeds"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val resolved = disputeService.updateStatus(dispute.id, DisputeStatus.Resolved, Some("was a re-ingested duplicate, deleted downstream"))

        assertEquals(resolved.status, DisputeStatus.Resolved)
        assertEquals(resolved.resolutionNote, Some("was a re-ingested duplicate, deleted downstream"))

  test("updateStatus to a non-Resolved status does not require a resolutionNote"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val investigating = disputeService.updateStatus(dispute.id, DisputeStatus.Investigating, None)

        assertEquals(investigating.status, DisputeStatus.Investigating)
        assertEquals(investigating.resolutionNote, None)

  test("updateStatus can reopen a Resolved dispute"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        disputeService.updateStatus(dispute.id, DisputeStatus.Resolved, Some("thought this was done"))

        val reopened = disputeService.updateStatus(dispute.id, DisputeStatus.Investigating, None)

        assertEquals(reopened.status, DisputeStatus.Investigating)
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `sbt "backend/testOnly perfectpair.domain.service.DisputeServiceSuite"`
Expected: compile failure (`DisputeService` doesn't exist yet) — that's the expected failing state before
implementation.

- [ ] **Step 7: Implement `DisputeService.scala`**

```scala
package perfectpair.domain.service

import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

class DisputeService(using
  disputeRepo:  DisputeRepo,
  movementRepo: MovementRepo,
  reconRepo:    ReconciliationRepo,
  eventStore:   EventStore,
  clock:        ClockService
):

  def raise(referenceId: ID, targetType: DisputeTarget, category: DisputeCategory, detail: Option[String]): Txn[Dispute] =
    targetType match
      case DisputeTarget.Movement       => movementRepo.findById(referenceId).ErrIfNotFound("movement", referenceId)
      case DisputeTarget.Reconciliation => reconRepo.findById(referenceId).ErrIfNotFound("reconciliation", referenceId)

    val now = clock.now
    val dispute = disputeRepo.insertReturning(
      Dispute(
        referenceId = referenceId,
        targetType  = targetType,
        category    = category,
        detail      = detail,
        createdAt   = Some(now),
        updatedAt   = Some(now)
      )
    )
    eventStore.publishEvent(DisputeRaised(disputeID = dispute.id))
    dispute

  def updateStatus(disputeId: ID, newStatus: DisputeStatus, resolutionNote: Option[String]): Txn[Dispute] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)
    if newStatus == DisputeStatus.Resolved && resolutionNote.forall(_.isBlank) then
      throw IllegalArgumentError("resolutionNote", "required when resolving a dispute")

    dispute.status = newStatus
    resolutionNote.foreach(n => dispute.resolutionNote = Some(n))
    dispute.updatedAt = Some(clock.now)
    disputeRepo.update(dispute)
    eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = newStatus.toString))
    dispute
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `sbt "backend/testOnly perfectpair.domain.service.DisputeServiceSuite"`
Expected: `8 tests, 0 failed` (or similar — all 8 tests from Step 5 passing).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/port/driven/DisputeRepo.scala backend/src/main/scala/perfectpair/adapter/driven/DisputeRepoLive.scala backend/src/main/scala/perfectpair/domain/model/Event.scala backend/src/test/scala/perfectpair/domain/DataSetup.scala backend/src/test/scala/perfectpair/domain/service/DisputeServiceSuite.scala backend/src/main/scala/perfectpair/domain/service/DisputeService.scala
git commit -m "Add DisputeService: raise a dispute on a Movement or Reconciliation, update its status

Resolving a dispute (status -> Resolved) requires a non-blank resolutionNote;
every other transition, including reopening a Resolved dispute, is
unrestricted for v1."
```

---

### Task 3: Shared DTOs (`common` module)

**Files:**
- Modify: `common/src/main/scala/perfectpair/common/http/http.scala`
- Modify: `common/src/main/scala/perfectpair/common/model/Payload.scala`

**Interfaces:**
- Produces: `HttpPath.dispute`, `HttpPath.disputes`; `DisputeTargetPayload`, `DisputeCategoryPayload`,
  `DisputeStatusPayload` enums; `RaiseDisputeRequest`, `UpdateDisputeStatusRequest`, `DisputePayload` case
  classes — consumed by Task 4's routes and payload conversion.

- [ ] **Step 1: Add path constants to `http.scala`**

Add these two lines to the `HttpPath` object, alongside the existing `reconciliation`/`reconciliations`
pair:

```scala
  val disputes        = s"$prefix/disputes"
  val dispute         = s"$prefix/dispute"
```

- [ ] **Step 2: Add the DTOs to `Payload.scala`**

Add this block after `MovementSpecAndAccounts` and before `combineAccountsAndMovementSpecs`:

```scala
enum DisputeTargetPayload derives ReadWriter:
  case Movement, Reconciliation

enum DisputeCategoryPayload derives ReadWriter:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality

enum DisputeStatusPayload derives ReadWriter:
  case New, Investigating, Escalated, Resolved

case class RaiseDisputeRequest(
  referenceId: String,
  targetType:  DisputeTargetPayload,
  category:    DisputeCategoryPayload,
  detail:      Option[String] = None
) derives ReadWriter

case class UpdateDisputeStatusRequest(
  status:         DisputeStatusPayload,
  resolutionNote: Option[String] = None
) derives ReadWriter

case class DisputePayload(
  id:              String,
  referenceId:     String,
  targetType:      DisputeTargetPayload,
  category:        DisputeCategoryPayload,
  detail:          Option[String],
  status:          DisputeStatusPayload,
  resolutionNote:  Option[String],
  accountId:       String,
  accountName:     String,
  referenceLabel:  String,
  createdAt:       OffsetDateTime,
  updatedAt:       OffsetDateTime
) derives ReadWriter
```

- [ ] **Step 3: Type-check**

Run: `sbt common/compile`
Expected: `[success]` with no errors.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/scala/perfectpair/common/http/http.scala common/src/main/scala/perfectpair/common/model/Payload.scala
git commit -m "Add Dispute path constants and shared DTOs to the common module"
```

---

### Task 4: Payload conversion, routes, and dependency wiring

**Files:**
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`
- Modify: `backend/src/main/scala/perfectpair/app/Main.scala`

**Interfaces:**
- Consumes: `Dispute`/`DisputeTarget`/`DisputeCategory`/`DisputeStatus` (Task 1), `DisputeService` (Task 2),
  `DisputePayload`/`RaiseDisputeRequest`/`UpdateDisputeStatusRequest`/`HttpPath.dispute(s)` (Task 3).
- Produces: `POST /api/v1/dispute`, `GET /api/v1/disputes`, `PUT /api/v1/dispute/:id/status` — the full
  feature, end-to-end.

- [ ] **Step 1: Add conversion functions to `adapter/driving/Payload.scala`**

Add this block after `movementfromDomainModel` (the last function in the file):

```scala
  def disputeTargetToDomain(p: DisputeTargetPayload): DisputeTarget = p match
    case DisputeTargetPayload.Movement       => DisputeTarget.Movement
    case DisputeTargetPayload.Reconciliation => DisputeTarget.Reconciliation

  def disputeTargetFromDomain(d: DisputeTarget): DisputeTargetPayload = d match
    case DisputeTarget.Movement       => DisputeTargetPayload.Movement
    case DisputeTarget.Reconciliation => DisputeTargetPayload.Reconciliation

  def disputeCategoryToDomain(p: DisputeCategoryPayload): DisputeCategory = p match
    case DisputeCategoryPayload.AmountMismatch     => DisputeCategory.AmountMismatch
    case DisputeCategoryPayload.MissingCounterpart => DisputeCategory.MissingCounterpart
    case DisputeCategoryPayload.Duplicate          => DisputeCategory.Duplicate
    case DisputeCategoryPayload.TimingDifference   => DisputeCategory.TimingDifference
    case DisputeCategoryPayload.DataQuality        => DisputeCategory.DataQuality

  def disputeCategoryFromDomain(d: DisputeCategory): DisputeCategoryPayload = d match
    case DisputeCategory.AmountMismatch     => DisputeCategoryPayload.AmountMismatch
    case DisputeCategory.MissingCounterpart => DisputeCategoryPayload.MissingCounterpart
    case DisputeCategory.Duplicate          => DisputeCategoryPayload.Duplicate
    case DisputeCategory.TimingDifference   => DisputeCategoryPayload.TimingDifference
    case DisputeCategory.DataQuality        => DisputeCategoryPayload.DataQuality

  def disputeStatusToDomain(p: DisputeStatusPayload): DisputeStatus = p match
    case DisputeStatusPayload.New           => DisputeStatus.New
    case DisputeStatusPayload.Investigating => DisputeStatus.Investigating
    case DisputeStatusPayload.Escalated     => DisputeStatus.Escalated
    case DisputeStatusPayload.Resolved      => DisputeStatus.Resolved

  def disputeStatusFromDomain(d: DisputeStatus): DisputeStatusPayload = d match
    case DisputeStatus.New           => DisputeStatusPayload.New
    case DisputeStatus.Investigating => DisputeStatusPayload.Investigating
    case DisputeStatus.Escalated     => DisputeStatusPayload.Escalated
    case DisputeStatus.Resolved      => DisputeStatusPayload.Resolved

  /** accountId/accountName/referenceLabel must be resolved by the caller (see Routes.scala) since which
    * repos to query depends on dispute.targetType. */
  def disputePayloadFromDomain(
    dispute:        Dispute,
    accountId:      String,
    accountName:    String,
    referenceLabel: String
  ): DisputePayload =
    DisputePayload(
      id             = dispute.id.toString,
      referenceId    = dispute.referenceId.toString,
      targetType     = disputeTargetFromDomain(dispute.targetType),
      category       = disputeCategoryFromDomain(dispute.category),
      detail         = dispute.detail,
      status         = disputeStatusFromDomain(dispute.status),
      resolutionNote = dispute.resolutionNote,
      accountId      = accountId,
      accountName    = accountName,
      referenceLabel = referenceLabel,
      createdAt      = dispute.createdAt.get,
      updatedAt      = dispute.updatedAt.get
    )
```

- [ ] **Step 2: Add the context-resolution helper and three routes to `Routes.scala`**

Add `disputeRepo: DisputeRepo` and `disputeService: DisputeService` to `App`'s constructor `using` clause
(alongside the existing `reconSpecRepo`/`eventRepo` params):

```scala
  disputeRepo:        DisputeRepo,
  disputeService:     DisputeService,
```

Add this private helper method (placed anywhere among the other `def`s in `App`, e.g. right before the
`corsDecorator` class at the bottom):

```scala
  private def disputeContext(dispute: Dispute): (String, String, String) =
    dispute.targetType match
      case DisputeTarget.Movement =>
        val movement = movementRepo.findById(dispute.referenceId).ErrIfNotFound("movement", dispute.referenceId)
        val account  = accountRepo.findById(movement.inAccountId).ErrIfNotFound("account", movement.inAccountId)
        (account.id.toString, account.name, movement.referenceId.toString)
      case DisputeTarget.Reconciliation =>
        val recon     = reconRepo.findById(dispute.referenceId).ErrIfNotFound("reconciliation", dispute.referenceId)
        val reconSpec = reconSpecRepo.findById(recon.specId).ErrIfNotFound("reconciliation spec", recon.specId)
        val account   = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
        (account.id.toString, account.name, recon.description)
```

Add these three routes (placed anywhere among the other `@cask.route` defs, e.g. after
`writeOffReconciliation`). **Note the raise route uses the singular `HttpPath.dispute`, not the plural
`HttpPath.disputes` used by list** — registering POST and GET on the exact same literal path string
collides in cask's CORS `options` dispatch (`HttpCmd.POST`/`HttpCmd.GET` both implicitly register an
`options` handler at that path), confirmed by hitting `More than one endpoint has the same path: options
/api/v1/disputes, options /api/v1/disputes` at runtime before fixing it. This matches how
`fundFlow`/`fundFlows` already split POST-create from GET-list earlier in this same file:

```scala
  @cask.route(HttpPath.dispute, methods = HttpCmd.POST)
  def raiseDispute(request: cask.Request): String =
    val payload = read[RaiseDisputeRequest](request.text())
    transact(transactor):
      val dispute = disputeService.raise(
        UUID.fromString(payload.referenceId),
        Payload.disputeTargetToDomain(payload.targetType),
        Payload.disputeCategoryToDomain(payload.category),
        payload.detail
      )
      val (accountId, accountName, referenceLabel) = disputeContext(dispute)
      write(Payload.disputePayloadFromDomain(dispute, accountId, accountName, referenceLabel))

  @cask.route(HttpPath.disputes, methods = HttpCmd.GET)
  def listDisputes(): String =
    transact(transactor):
      val disputes = disputeRepo.findAll
      val payload = disputes.map { d =>
        val (accountId, accountName, referenceLabel) = disputeContext(d)
        Payload.disputePayloadFromDomain(d, accountId, accountName, referenceLabel)
      }
      write(payload)

  @cask.route(s"${HttpPath.dispute}/:id/status", methods = HttpCmd.PUT)
  def updateDisputeStatus(id: String, request: cask.Request): String =
    val payload = read[UpdateDisputeStatusRequest](request.text())
    transact(transactor):
      val dispute = disputeService.updateStatus(
        UUID.fromString(id),
        Payload.disputeStatusToDomain(payload.status),
        payload.resolutionNote
      )
      val (accountId, accountName, referenceLabel) = disputeContext(dispute)
      write(Payload.disputePayloadFromDomain(dispute, accountId, accountName, referenceLabel))
```

- [ ] **Step 3: Wire the new dependencies into `Main.scala`**

Add two lines alongside the existing `given` declarations (e.g. right after `given manualReconciler:
ManualReconciler = ManualReconciler()`):

```scala
    given disputeRepo:    DisputeRepo    = DisputeRepoLive()
    given disputeService: DisputeService = DisputeService()
```

- [ ] **Step 4: Type-check**

Run: `sbt backend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Manual smoke test of all three routes**

Start the backend: `sbt backend/run` (wait for it to bind — confirm with `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/`, expect a non-`000` response).

Get a reference to dispute — any existing unreconciled movement's id works. If you don't have one handy,
query one directly:

```bash
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -t -c "select id from recon.movement where reconciliation_id is null limit 1;"
```

Raise a dispute on it (substitute the id from above):

```bash
curl -s -X POST http://localhost:8080/api/v1/dispute \
  -H "Content-Type: application/json" \
  -d '{"referenceId":"<movement-id>","targetType":"Movement","category":"MissingCounterpart","detail":"smoke test"}'
```

Expected: a JSON `DisputePayload` body with `"status":"New"`, `"category":"MissingCounterpart"`, and a
populated `accountName`/`referenceLabel`. Note the `"id"` field from the response for the next two calls.

```bash
curl -s http://localhost:8080/api/v1/disputes
```

Expected: a JSON array containing the dispute just raised.

```bash
curl -s -X PUT http://localhost:8080/api/v1/dispute/<dispute-id>/status \
  -H "Content-Type: application/json" \
  -d '{"status":"Resolved"}'
```

Expected: an error response (missing `resolutionNote` when resolving). Retry with a note:

```bash
curl -s -X PUT http://localhost:8080/api/v1/dispute/<dispute-id>/status \
  -H "Content-Type: application/json" \
  -d '{"status":"Resolved","resolutionNote":"smoke test resolved"}'
```

Expected: `"status":"Resolved"`, `"resolutionNote":"smoke test resolved"`.

Stop the backend (`Ctrl-C` or kill the process on port 8080) when done.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/perfectpair/adapter/driving/Payload.scala backend/src/main/scala/perfectpair/adapter/driving/Routes.scala backend/src/main/scala/perfectpair/app/Main.scala
git commit -m "Wire up Dispute routes: raise, list, update status

POST /api/v1/dispute, GET /api/v1/disputes, PUT /api/v1/dispute/:id/status.
Each response denormalizes account name and a reference label (movement
referenceId or reconciliation description) by composing existing repo calls
in the route handler, matching how getFundFlowMainView already does this
rather than a raw SQL join."
```

---

## Self-Review

**Spec coverage:** Naming (`Dispute`, not `Exception` — Task 1) · polymorphic `referenceId`/`targetType`
model (Task 1) · schema + indexes (Task 1) · repo with no custom methods (Task 2) · service `raise`/
`updateStatus` with the resolution-note requirement and no other transition guards (Task 2) · events (Task
2) · shared DTOs (Task 3) · three routes with denormalized list context (Task 4) · dependency wiring (Task
4). Every section of the design doc maps to a task. "Out of Scope" items (automatic detection, assignee,
transition guards, mutating the target, frontend work, server-side list filtering) are untouched by every
task.

**Placeholder scan:** No TBD/TODO. Every step has complete, copy-pasteable code — including the full test
suite (8 tests) written out in Task 2 rather than described.

**Type consistency:** `DisputeService.raise(referenceId: ID, targetType: DisputeTarget, category:
DisputeCategory, detail: Option[String]): Txn[Dispute]` and `.updateStatus(disputeId: ID, newStatus:
DisputeStatus, resolutionNote: Option[String]): Txn[Dispute]` have identical signatures everywhere they're
referenced (Task 2's tests, Task 4's routes). `Payload.disputePayloadFromDomain(dispute: Dispute, accountId:
String, accountName: String, referenceLabel: String): DisputePayload` matches its one call shape used three
times in Task 4's routes. `DisputeRepo`/`DisputeRepoLive` names match between Task 2 (definition) and Task 4
(constructor `using` clause, `Main.scala` wiring).
