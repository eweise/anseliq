# Exceptions v2 — Comment/History Thread + Detail Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Dispute.resolutionNote` and the list page's inline status-change buttons with a unified
`DisputeComment` thread (status change + comment as one operation) and a new detail page showing the full
target entity plus the thread.

**Architecture:** Same hexagonal layering as the v1 Disputes work: `domain/model` → `domain/port/driven` →
`adapter/driven` → `domain/service` → `adapter/driving` → `common/model` → frontend. `DisputeService.
updateStatus` is *replaced* by `addComment` (not added alongside it) — every existing caller and test
migrates to the new method in the same task that removes the old one, so the codebase never sits in a state
with two competing ways to change a dispute's status.

**Tech Stack:** Scala 3, Magnum, PostgreSQL, cask, munit + `rollBackOnly`, Laminar, frontroute.

## Global Constraints

- Design doc: `docs/superpowers/specs/2026-08-13-exceptions-v2-history-comments-design.md` — every field
  name, signature, and route shape below is copied from it verbatim.
- The local Postgres `recon_db` is shared — schema changes go in via additive/targeted `psql` DDL (an
  `ALTER TABLE ... DROP COLUMN` and a `CREATE TABLE`), never `make create-db`.
- `PUT /api/v1/dispute/:id/status`, `UpdateDisputeStatusRequest`, and `DisputeService.updateStatus` are
  **removed**, not deprecated-in-place. `Dispute.resolutionNote` and `DisputePayload.resolutionNote` are
  **removed** too.
- No automatic/system-generated comments. No comment editing/deletion. No pagination on the thread. No
  change to how disputes are raised.
- Verify with `sbt backend/compile` / `sbt frontend/compile` after each task, plus the specified test runs
  and live checks — this plan touches enough surface area (schema, service, two removed + two new routes,
  two DTOs removed/added, two pages) that compilation alone won't catch behavioral regressions.

---

### Task 1: Schema + domain model

**Files:**
- Modify: `backend/db/schema.sql`
- Modify: `backend/src/main/scala/onparity/domain/model/Dispute.scala`

**Interfaces:**
- Produces: `DisputeComment` case class (`id`, `disputeId`, `body: Option[String]`,
  `newStatus: Option[DisputeStatus]`, `createdAt`) — consumed by every later task. `Dispute` loses
  `resolutionNote`.

- [ ] **Step 1: Update `schema.sql`**

Add after the existing `recon.dispute` table definition:

```sql
ALTER TABLE recon.dispute DROP COLUMN resolution_note;

create table recon.dispute_comment
(
    id         uuid primary key,
    dispute_id uuid not null REFERENCES recon.dispute (id) ON DELETE CASCADE,
    body       text,
    new_status text,
    created_at timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_comment_dispute_id ON recon.dispute_comment (dispute_id, created_at);
```

- [ ] **Step 2: Apply to the local `recon_db` additively**

```bash
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "ALTER TABLE recon.dispute DROP COLUMN resolution_note;"
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "
create table recon.dispute_comment
(
    id         uuid primary key,
    dispute_id uuid not null REFERENCES recon.dispute (id) ON DELETE CASCADE,
    body       text,
    new_status text,
    created_at timestamptz default CURRENT_TIMESTAMP not null
);"
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "CREATE INDEX dispute_comment_dispute_id ON recon.dispute_comment (dispute_id, created_at);"
```

Expected: `ALTER TABLE`, then `CREATE TABLE`, then `CREATE INDEX` confirmations, no errors.

- [ ] **Step 3: Update `Dispute.scala`**

Remove the `resolutionNote` field from `Dispute`, and add `DisputeComment` alongside it:

```scala
package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.ID

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
  @Id id:       ID                     = createID(),
  referenceId:  ID,
  targetType:   DisputeTarget,
  category:     DisputeCategory,
  detail:       Option[String]         = None,
  var status:    DisputeStatus          = DisputeStatus.New,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Dispute:
  val Table = TableInfo[Dispute, Dispute, UUID]

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class DisputeComment(
  @Id id:        ID                     = createID(),
  disputeId:     ID,
  body:          Option[String]         = None,
  newStatus:     Option[DisputeStatus]  = None,
  var createdAt: Option[OffsetDateTime] = None
) derives DbCodec

object DisputeComment:
  val Table = TableInfo[DisputeComment, DisputeComment, UUID]
```

- [ ] **Step 4: Type-check**

Run: `sbt backend/compile`
Expected: errors — `DisputeService.scala`, `Routes.scala`, and `Payload.scala` all still reference
`resolutionNote`/`updateStatus`. This is expected; Tasks 2 and 4 fix them. Confirm the errors are all in
those two files and mention `resolutionNote` or `updateStatus`, not something unrelated.

- [ ] **Step 5: Commit**

```bash
git add backend/db/schema.sql backend/src/main/scala/onparity/domain/model/Dispute.scala
git commit -m "Add DisputeComment, remove Dispute.resolutionNote

resolutionNote becomes redundant once the comment thread exists - the 'why'
for a resolution is just the body of whichever comment set newStatus to
Resolved. Backend won't compile again until Task 2 (service) and Task 4
(routes/payload) catch up - expected intermediate state."
```

---

### Task 2: `DisputeCommentRepo`, `DisputeService.addComment`, migrated tests

**Files:**
- Create: `backend/src/main/scala/onparity/domain/port/driven/DisputeCommentRepo.scala`
- Create: `backend/src/main/scala/onparity/adapter/driven/DisputeCommentRepoLive.scala`
- Modify: `backend/src/main/scala/onparity/domain/service/DisputeService.scala`
- Modify: `backend/src/test/scala/onparity/domain/DataSetup.scala`
- Modify: `backend/src/test/scala/onparity/domain/service/DisputeServiceSuite.scala`

**Interfaces:**
- Consumes: `DisputeComment`, `DisputeStatus` from Task 1.
- Produces: `DisputeCommentRepo.findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]`;
  `DisputeService.addComment(disputeId: ID, body: Option[String], newStatus: Option[DisputeStatus]):
  Txn[DisputeComment]` — consumed by Task 4's routes. `DisputeService.updateStatus` no longer exists.

- [ ] **Step 1: Create the repo port**

```scala
package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.DisputeComment

trait DisputeCommentRepo extends DomainRepo[DisputeComment, DisputeComment]:
  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]
```

- [ ] **Step 2: Create the repo adapter**

```scala
package onparity.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model.DisputeComment
import onparity.domain.port.driven.{DisputeCommentRepo, Txn}

import java.util.UUID

class DisputeCommentRepoLive extends Repo[DisputeComment, DisputeComment, UUID] with DisputeCommentRepo:
  val c = DisputeComment.Table

  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]] =
    sql"""
      SELECT ${c.all}
      FROM $c AS a
      WHERE dispute_id = $disputeId
      ORDER BY created_at
    """.query[DisputeComment].run()
```

- [ ] **Step 3: Wire `disputeCommentRepo` into the shared test fixture**

In `DataSetup.scala`, add one line among the existing `given`s (e.g. right after
`given disputeRepo: DisputeRepo = DisputeRepoLive()` — check the exact current line before editing, since
Task 1 didn't touch this file and its existing content should still match what shipped with the v1 Disputes
plan):

```scala
  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()
```

- [ ] **Step 4: Replace `DisputeServiceSuite.scala` with the migrated + expanded test suite**

This replaces the whole file — every `updateStatus` call becomes `addComment`, three new tests cover the
plain-comment and ordering behavior `updateStatus` never had:

```scala
package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, EventRepoLive, JobRepoLive}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{DisputeCategory, DisputeStatus, DisputeTarget, IllegalArgumentError, Movement, MovementSpec, NotFoundError}
import onparity.domain.port.driven.{JobRepo, Txn}
import onparity.testing.rollBackOnly

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

  test("addComment to Resolved without a body errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, Some(DisputeStatus.Resolved))

  test("addComment to Resolved with a body succeeds and updates the dispute's status"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, Some("was a re-ingested duplicate, deleted downstream"), Some(DisputeStatus.Resolved))

        assertEquals(comment.body, Some("was a re-ingested duplicate, deleted downstream"))
        assertEquals(comment.newStatus, Some(DisputeStatus.Resolved))

        val updated = data.disputeRepo.findById(dispute.id).get
        assertEquals(updated.status, DisputeStatus.Resolved)

  test("addComment to a non-Resolved status does not require a body"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        assertEquals(comment.newStatus, Some(DisputeStatus.Investigating))
        assertEquals(comment.body, None)

        val updated = data.disputeRepo.findById(dispute.id).get
        assertEquals(updated.status, DisputeStatus.Investigating)

  test("addComment can reopen a Resolved dispute"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        disputeService.addComment(dispute.id, Some("thought this was done"), Some(DisputeStatus.Resolved))

        disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        val reopened = data.disputeRepo.findById(dispute.id).get
        assertEquals(reopened.status, DisputeStatus.Investigating)

  test("addComment with no status change requires a body"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, None)

  test("addComment with no status change and a body just adds a comment, leaving status unchanged"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, Some("still looking into this"), None)

        assertEquals(comment.body, Some("still looking into this"))
        assertEquals(comment.newStatus, None)

        val unchanged = data.disputeRepo.findById(dispute.id).get
        assertEquals(unchanged.status, DisputeStatus.New)

  test("comments are returned in chronological order"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        disputeService.addComment(dispute.id, Some("first"), None)
        disputeService.addComment(dispute.id, Some("second"), Some(DisputeStatus.Investigating))
        disputeService.addComment(dispute.id, Some("third"), None)

        val thread = data.disputeCommentRepo.findByDisputeId(dispute.id)
        assertEquals(thread.map(_.body), Vector(Some("first"), Some("second"), Some("third")))
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `sbt "backend/testOnly onparity.domain.service.DisputeServiceSuite"`
Expected: compile failure — `DisputeService.addComment` doesn't exist yet, `data.disputeCommentRepo` doesn't
resolve (Step 3's `DataSetup` edit alone doesn't make the service compile). This is the expected failing
state before Step 6.

- [ ] **Step 6: Replace `DisputeService.scala`'s `updateStatus` with `addComment`**

```scala
package onparity.domain.service

import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.port.driven.*

class DisputeService(using
  disputeRepo:  DisputeRepo,
  commentRepo:  DisputeCommentRepo,
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

  def addComment(disputeId: ID, body: Option[String], newStatus: Option[DisputeStatus]): Txn[DisputeComment] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)

    val bodyRequired = newStatus match
      case None                         => true
      case Some(DisputeStatus.Resolved) => true
      case Some(_)                      => false
    if bodyRequired && body.forall(_.isBlank) then
      throw IllegalArgumentError("body", "required for this entry")

    newStatus.foreach { s =>
      dispute.status = s
      dispute.updatedAt = Some(clock.now)
      disputeRepo.update(dispute)
      eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = s.toString))
    }

    val comment = commentRepo.insertReturning(
      DisputeComment(disputeId = disputeId, body = body, newStatus = newStatus, createdAt = Some(clock.now))
    )
    eventStore.publishEvent(DisputeCommentAdded(disputeID = disputeId, commentID = comment.id))
    comment
```

- [ ] **Step 7: Add the `DisputeCommentAdded` event**

In `backend/src/main/scala/onparity/domain/model/Event.scala`, add after the existing
`DisputeStatusChanged`:

```scala
case class DisputeCommentAdded(id: ID = UUID.randomUUID(), disputeID: ID, commentID: ID) extends Event
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `sbt "backend/testOnly onparity.domain.service.DisputeServiceSuite"`
Expected: `10 tests, 0 failed`.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/scala/onparity/domain/port/driven/DisputeCommentRepo.scala backend/src/main/scala/onparity/adapter/driven/DisputeCommentRepoLive.scala backend/src/main/scala/onparity/domain/service/DisputeService.scala backend/src/main/scala/onparity/domain/model/Event.scala backend/src/test/scala/onparity/domain/DataSetup.scala backend/src/test/scala/onparity/domain/service/DisputeServiceSuite.scala
git commit -m "Replace DisputeService.updateStatus with addComment

A status change is now just a comment with newStatus set; a plain comment
(newStatus = None) requires a body since an empty entry would be meaningless.
Migrates all 8 existing tests to the new method and adds 3 more covering the
plain-comment and thread-ordering behavior updateStatus never had."
```

---

### Task 3: Shared DTOs (`common` module)

**Files:**
- Modify: `common/src/main/scala/onparity/common/model/Payload.scala`

**Interfaces:**
- Produces: `DisputeCommentPayload`, `DisputeDetailPayload`, `AddDisputeCommentRequest`. `DisputePayload`
  loses `resolutionNote`. `UpdateDisputeStatusRequest` is deleted.

- [ ] **Step 1: Edit the Dispute DTO block**

Replace this (the current block, `UpdateDisputeStatusRequest` through the end of `DisputePayload`):

```scala
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

with:

```scala
case class DisputePayload(
  id:              String,
  referenceId:     String,
  targetType:      DisputeTargetPayload,
  category:        DisputeCategoryPayload,
  detail:          Option[String],
  status:          DisputeStatusPayload,
  accountId:       String,
  accountName:     String,
  referenceLabel:  String,
  createdAt:       OffsetDateTime,
  updatedAt:       OffsetDateTime
) derives ReadWriter

case class DisputeCommentPayload(
  id:        String,
  body:      Option[String],
  newStatus: Option[DisputeStatusPayload],
  createdAt: OffsetDateTime
) derives ReadWriter

case class DisputeDetailPayload(
  dispute:        DisputePayload,
  movement:       Option[MovementPayload],
  reconciliation: Option[ReconciliationPayload],
  comments:       Seq[DisputeCommentPayload]
) derives ReadWriter

case class AddDisputeCommentRequest(
  body:      Option[String]              = None,
  newStatus: Option[DisputeStatusPayload] = None
) derives ReadWriter
```

- [ ] **Step 2: Type-check**

Run: `sbt commonJVM/compile`
Expected: `[success]` with no errors. (This is a cross-project; `common/compile` is not a valid sbt task
here — confirmed during the v1 Disputes plan.)

- [ ] **Step 3: Commit**

```bash
git add common/src/main/scala/onparity/common/model/Payload.scala
git commit -m "Add DisputeComment/DisputeDetail DTOs, remove resolutionNote and UpdateDisputeStatusRequest"
```

---

### Task 4: Backend routes, payload conversion, wiring

**Files:**
- Modify: `backend/src/main/scala/onparity/adapter/driving/Payload.scala`
- Modify: `backend/src/main/scala/onparity/adapter/driving/Routes.scala`
- Modify: `backend/src/main/scala/onparity/app/Main.scala`

**Interfaces:**
- Consumes: `DisputeCommentRepo`/`DisputeService.addComment` (Task 2), `DisputeDetailPayload`/
  `DisputeCommentPayload`/`AddDisputeCommentRequest` (Task 3).
- Produces: `GET /api/v1/dispute/:id`, `POST /api/v1/dispute/:id/comments`. `PUT /api/v1/dispute/:id/status`
  no longer exists.

- [ ] **Step 1: Update `adapter/driving/Payload.scala`**

Remove `resolutionNote = dispute.resolutionNote` from `disputePayloadFromDomain` (it now has one fewer
field, matching Task 3's `DisputePayload`):

```scala
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
      accountId      = accountId,
      accountName    = accountName,
      referenceLabel = referenceLabel,
      createdAt      = dispute.createdAt.get,
      updatedAt      = dispute.updatedAt.get
    )
```

Add after it (this is the last function in the file):

```scala

  def disputeCommentPayloadFromDomain(c: DisputeComment): DisputeCommentPayload =
    DisputeCommentPayload(
      id        = c.id.toString,
      body      = c.body,
      newStatus = c.newStatus.map(disputeStatusFromDomain),
      createdAt = c.createdAt.get
    )
```

- [ ] **Step 2: Update `Routes.scala`**

Add `disputeCommentRepo: DisputeCommentRepo` to `App`'s constructor `using` clause (alongside the existing
`disputeRepo`/`disputeService`):

```scala
  disputeRepo:        DisputeRepo,
  disputeCommentRepo: DisputeCommentRepo,
  disputeService:     DisputeService
```

Replace the `updateDisputeStatus` route (delete it entirely) with two new routes. The full "Disputes"
section becomes:

```scala
  // Disputes
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

  @cask.route(s"${HttpPath.dispute}/:id", methods = HttpCmd.GET)
  def getDispute(id: String): String =
    transact(transactor):
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  @cask.route(s"${HttpPath.dispute}/:id/comments", methods = HttpCmd.POST)
  def addDisputeComment(id: String, request: cask.Request): String =
    val payload = read[AddDisputeCommentRequest](request.text())
    transact(transactor):
      disputeService.addComment(
        UUID.fromString(id),
        payload.body,
        payload.newStatus.map(Payload.disputeStatusToDomain)
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  private def disputeDetail(dispute: Dispute): DisputeDetailPayload =
    val (accountId, accountName, referenceLabel) = disputeContext(dispute)
    val disputePayload = Payload.disputePayloadFromDomain(dispute, accountId, accountName, referenceLabel)
    val movementPayload = dispute.targetType match
      case DisputeTarget.Movement => movementRepo.findById(dispute.referenceId).map(Payload.movementfromDomainModel)
      case DisputeTarget.Reconciliation => None
    val reconPayload = dispute.targetType match
      case DisputeTarget.Reconciliation => reconRepo.findById(dispute.referenceId).map(Payload.reconFromDomain)
      case DisputeTarget.Movement => None
    val comments = disputeCommentRepo.findByDisputeId(dispute.id).map(Payload.disputeCommentPayloadFromDomain)
    DisputeDetailPayload(disputePayload, movementPayload, reconPayload, comments)

  private def disputeContext(dispute: Dispute): Txn[(String, String, String)] =
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

(`disputeContext` itself is unchanged — only the two new routes and the `disputeDetail` helper are new;
`raiseDispute`/`listDisputes` are shown for placement context but are otherwise untouched.)

- [ ] **Step 3: Wire `disputeCommentRepo` into `Main.scala`**

```scala
    given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()
```

Add it right after the existing `given disputeRepo: DisputeRepo = DisputeRepoLive()` line, before
`given disputeService: DisputeService = DisputeService()` (ordering matters: `DisputeService` now needs
`DisputeCommentRepo` in its `using` clause).

- [ ] **Step 4: Type-check**

Run: `sbt backend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Manual smoke test**

Start the backend (`sbt backend/run`), wait for it to respond. Raise a fresh dispute (reuse the pattern from
the v1 plan — grab an unreconciled movement id via `psql`, `POST /api/v1/dispute`), then:

```bash
curl -s http://localhost:8080/api/v1/dispute/<dispute-id>
```

Expected: a `DisputeDetailPayload` — `dispute` populated, `movement` populated (since you raised it on a
Movement), `reconciliation` is `null`, `comments` is an empty array.

```bash
curl -s -X POST http://localhost:8080/api/v1/dispute/<dispute-id>/comments \
  -H "Content-Type: application/json" \
  -d '{"body":"looking into this now","newStatus":"Investigating"}'
```

Expected: the same `DisputeDetailPayload` shape, `dispute.status` now `"Investigating"`, `comments` has one
entry with `body: "looking into this now"`, `newStatus: "Investigating"`.

```bash
curl -s -X POST http://localhost:8080/api/v1/dispute/<dispute-id>/comments \
  -H "Content-Type: application/json" \
  -d '{"body":"just a note, no status change"}'
```

Expected: `comments` now has two entries; the dispute's `status` is still `"Investigating"` (unchanged by a
plain comment).

```bash
curl -s -X PUT http://localhost:8080/api/v1/dispute/<dispute-id>/status
```

Expected: a 404 or routing error — this endpoint no longer exists.

Stop the backend when done.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/onparity/adapter/driving/Payload.scala backend/src/main/scala/onparity/adapter/driving/Routes.scala backend/src/main/scala/onparity/app/Main.scala
git commit -m "Add GET /dispute/:id and POST /dispute/:id/comments, remove PUT .../status

The detail endpoint composes the dispute, the full target entity (Movement
or Reconciliation, not just the list view's denormalized label), and the
comment thread in one response. Verified end-to-end with curl."
```

---

### Task 5: Frontend — detail page, simplified list

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/ExceptionDetailPage.scala`
- Modify: `frontend/src/main/scala/onparity/view/pages/ExceptionsPage.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Nav.scala`

**Interfaces:**
- Consumes: `DisputeDetailPayload`, `DisputeCommentPayload`, `AddDisputeCommentRequest` (Task 3),
  `GET/POST /api/v1/dispute/:id[/comments]` (Task 4).
- Produces: `ExceptionDetailPage.apply(id: String): HtmlElement`, reachable at
  `/components/exceptions/:id`; `Nav.toExceptionDetail(id: String): Unit`.

- [ ] **Step 1: Add the nav helper**

In `Nav.scala`, add alongside the existing helpers:

```scala
  def toExceptionDetail(id: String): Unit = push(s"/components/exceptions/$id")
```

- [ ] **Step 2: Create `ExceptionDetailPage.scala`**

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AddDisputeCommentRequest, DisputeCommentPayload, DisputeDetailPayload, DisputeStatusPayload, MovementPayload, ReconciliationPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, Form, PageHeader, Pill}
import upickle.default.*

object ExceptionDetailPage:

  case class State(detail: Option[DisputeDetailPayload] = None)

  private val statusOptions: Seq[(String, String)] =
    ("", "No change") +: Seq(
      DisputeStatusPayload.New,
      DisputeStatusPayload.Investigating,
      DisputeStatusPayload.Escalated,
      DisputeStatusPayload.Resolved
    ).map(s => s.toString -> s.toString)

  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  def apply(id: String): HtmlElement =
    val state       = Var(State())
    val loadBus     = EventBus[Unit]()
    val submitBus   = EventBus[(Option[DisputeStatusPayload], Option[String])]()
    val statusVar   = Var("")
    val bodyVar     = Var("")

    def load() =
      ApiClient.get[DisputeDetailPayload](HttpPath.url(s"${HttpPath.dispute}/$id"), SessionStore.token)

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(d) => state.update(_.copy(detail = Some(d)))
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      submitBus.events.flatMapSwitch { (newStatus, body) =>
        ApiClient.post[DisputeDetailPayload, AddDisputeCommentRequest](
          HttpPath.url(s"${HttpPath.dispute}/$id/comments"),
          AddDisputeCommentRequest(body, newStatus),
          SessionStore.token
        )
      } --> {
        case Right(d) =>
          state.update(_.copy(detail = Some(d)))
          statusVar.set("")
          bodyVar.set("")
        case Left(e) => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-4xl",
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.push("/components/exceptions"))),
        child <-- state.signal.map {
          case State(None) => div()
          case State(Some(d)) =>
            div(
              cls := "text-left",
              PageHeader.title(s"Dispute — ${d.dispute.category.toString}"),
              div(
                cls := "flex gap-2 mb-4",
                Pill(d.dispute.status.toString, statusTone(d.dispute.status)),
                Pill(d.dispute.targetType.toString, Pill.Tone.Neutral)
              ),
              targetDetails(d),
              h3(cls := "font-medium mt-6 mb-2", "Thread"),
              div(cls := "flex flex-col gap-3 mb-6", d.comments.map(commentEntry)*),
              commentForm(statusVar, bodyVar, submitBus)
            )
        }
      )
    )

  private def targetDetails(d: DisputeDetailPayload): HtmlElement =
    div(
      cls := "border border-md-outline rounded-lg p-4 mb-4",
      div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-2", s"Account: ${d.dispute.accountName}"),
      d.dispute.detail.map(det => div(cls := "text-sm mb-2", s"Raised with: $det")).getOrElse(div()),
      (d.movement, d.reconciliation) match
        case (Some(m), _) => movementDetails(m)
        case (_, Some(r)) => reconciliationDetails(r)
        case _            => div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "Target entity not found")
    )

  private def movementDetails(m: MovementPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1",
      div(s"Reference ID: ${m.referenceId}"),
      div(s"Amount: ${m.amount}"),
      div(s"In Account: ${m.inAccountId}"),
      div(s"Out Account: ${m.outAccountId}")
    )

  private def reconciliationDetails(r: ReconciliationPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1",
      div(s"Description: ${r.description}"),
      div(s"Applied Rules: ${r.appliedRules}"),
      div(s"Balance: ${r.balance}"),
      div(s"Status: ${r.status}"),
      div(s"Created: ${ViewHelper.formatDateTime(r.createdAt)}"),
      div(s"Updated: ${ViewHelper.formatDateTime(r.updatedAt)}")
    )

  private def commentEntry(c: DisputeCommentPayload): HtmlElement =
    div(
      cls := "border-l-2 border-md-outline pl-3",
      c.newStatus.map(s => Pill(s"→ ${s.toString}", statusTone(s))).getOrElse(div()),
      c.body.map(b => div(cls := "text-sm mt-1", b)).getOrElse(div()),
      div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-1", ViewHelper.formatDateTime(c.createdAt))
    )

  private def commentForm(
    statusVar: Var[String],
    bodyVar:   Var[String],
    submitBus: EventBus[(Option[DisputeStatusPayload], Option[String])]
  ): HtmlElement =
    div(
      cls := "border-t border-md-outline pt-4",
      Form.selectField("newStatus", "Change status", statusOptions, "", v => statusVar.set(v)),
      div(
        cls := "mb-4",
        input(
          typ         := "text",
          placeholder := "Add a comment",
          cls         := "w-full px-4 py-3.5 border border-md-outline rounded text-md-on-surface dark:text-white bg-transparent",
          onInput.mapToValue --> Observer[String](v => bodyVar.set(v))
        )
      ),
      Buttons.linkButton(
        "Post",
        Observer[Unit](_ =>
          val newStatus = DisputeStatusPayload.values.find(_.toString == statusVar.now())
          val body      = Some(bodyVar.now()).filter(_.nonEmpty)
          submitBus.emit((newStatus, body))
        )
      )
    )
```

- [ ] **Step 3: Simplify `ExceptionsPage.scala`**

Replace the whole file — the inline action buttons, the `editingResolution`/`resolutionInput` machinery, and
the `updateStatusBus` are all removed; rows become click-through links to the detail page:

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{DisputePayload, DisputeStatusPayload, DisputeTargetPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(disputes: Seq[DisputePayload] = Seq.empty)

  def apply(): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[Seq[DisputePayload]](HttpPath.url(HttpPath.disputes), SessionStore.token)

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(ds) => state.update(_.copy(disputes = ds))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-7xl",
        PageHeader.title("Exceptions & Disputes"),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Status", "Category", "Type", "Account", "Reference", "Detail"),
            s.disputes.map(renderRow)
          )
        }
      )
    )

  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  private def targetLabel(targetType: DisputeTargetPayload): String = targetType match
    case DisputeTargetPayload.Movement       => "Movement"
    case DisputeTargetPayload.Reconciliation => "Reconciliation"

  private def renderRow(d: DisputePayload): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Accent),
      Pill(targetLabel(d.targetType), Pill.Tone.Neutral),
      span(d.accountName),
      span(d.referenceLabel),
      a(
        href := s"/components/exceptions/${d.id}",
        cls  := "text-md-primary hover:underline cursor-pointer",
        onClick --> { e =>
          e.preventDefault()
          Nav.toExceptionDetail(d.id)
        },
        d.detail.getOrElse("View")
      )
    )
```

- [ ] **Step 4: Add the detail route**

In `Routes.scala`, add alongside the existing `path("exceptions")(ExceptionsPage())`:

```scala
          path("exceptions" / segment)(id => ExceptionDetailPage(id)),
```

(Place it in the `firstMatch(...)` list — order relative to the bare `"exceptions"` path doesn't matter,
`frontroute` already disambiguates bare paths from `/segment` paths elsewhere in this file, e.g.
`"fund-flows"` vs. `"accounts" / segment / "maintain"`.)

- [ ] **Step 5: Type-check**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 6: Visual check**

Start the backend and frontend dev server. Log in, navigate to Exceptions & Disputes, click a dispute's
"Detail"/reference link. Confirm: the detail page shows the status/category/type pills, the raised-with
detail line, and full target details (for a Movement: reference ID/amount/accounts; for a Reconciliation:
description/rules/balance/status/timestamps). Post a comment with a status change selected — confirm the
thread grows with a new entry showing the status-change pill and body text, and the header pills update to
the new status. Post a plain comment (no status change) — confirm it appears in the thread with no
status-change pill, and the header status is unchanged. Click "Back" — confirm it returns to the list and
the list's status pill reflects the change.

Stop both dev servers when done.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ExceptionDetailPage.scala frontend/src/main/scala/onparity/view/pages/ExceptionsPage.scala frontend/src/main/scala/onparity/routing/Routes.scala frontend/src/main/scala/onparity/routing/Nav.scala
git commit -m "Add ExceptionDetailPage with a comment/status thread, simplify the list to click-through

Status changes move off the list page entirely - rows now link to a detail
page showing the full target entity (Movement or Reconciliation) and a
unified thread where a status change is just a comment with newStatus set.
Verified live: posting a status-changing comment and a plain comment both
update the thread and (for the former) the header status correctly."
```

---

## Self-Review

**Spec coverage:** Naming/model shape (`DisputeComment`, `resolutionNote` removal — Task 1) · repo with one
custom finder, ascending order (Task 2) · service `addComment` replacing `updateStatus`, validation rules
matching the three-case table from the spec exactly (Task 2) · existing-test migration, not just new tests
(Task 2) · DTOs added/removed (Task 3) · both new routes + old route removal + detail composition reusing
`disputeContext` (Task 4) · frontend detail page + list simplification + routing (Task 5). Every section of
the design doc maps to a task. "Out of Scope" items (comment editing/deletion, automatic comments, raise
flow changes, pagination) are untouched by every task.

**Placeholder scan:** No TBD/TODO. Every step has complete code, including the full 10-test suite in Task 2
and the full page bodies in Task 5, not descriptions of what to write.

**Type consistency:** `DisputeService.addComment(disputeId: ID, body: Option[String], newStatus:
Option[DisputeStatus]): Txn[DisputeComment]` has an identical signature in Task 2's tests, Task 2's
implementation, and Task 4's route. `DisputeCommentRepo.findByDisputeId(disputeId: ID):
Txn[Vector[DisputeComment]]` matches between Task 2's port/adapter and its use in Task 4's `disputeDetail`
helper and Task 2's own ordering test. `DisputeDetailPayload`'s four fields (`dispute`, `movement`,
`reconciliation`, `comments`) are identical in Task 3's definition, Task 4's construction, and Task 5's
consumption.
