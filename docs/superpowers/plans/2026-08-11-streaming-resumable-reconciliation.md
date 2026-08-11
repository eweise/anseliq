# Streaming, Resumable Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `InMemoryReconciler`'s full in-memory fetch-and-sort of every unreconciled movement with SQL-level ordering and keyset-paginated streaming, and replace its one-giant-transaction write pattern with batched, checkpointed commits — without changing the existing `reconcileAccount` method or breaking any of its current callers/tests.

**Architecture:** `reconcileAccount` stays exactly as it is today. A new method, `reconcileAccountResumable`, is added alongside it: it pages through both sides of a match (in/out) via keyset pagination on the rule's match-key expression evaluated in SQL, runs the same two-pointer merge-join algorithm the existing code already uses, and commits every `batchSize` matches in its own transaction. An optional `jobId` lets it persist a per-attempt checkpoint (on a new `job.progress_json` column) so a crashed run can resume — the checkpoint is only ever used to resume that same attempt, never to bound eligibility beyond `reconciliation_id IS NULL`, so a fresh trigger always sees late-arriving movements regardless of where a previous run left off. Production call sites (`Main.scala`'s Reconciliation job poller, `Routes.scala`'s manual HTTP endpoint) switch to the new method.

**Tech Stack:** Scala 3, Magnum (SQL/transactions), PostgreSQL, munit.

## Global Constraints

- `reconcileAccount(accountId): Txn[ReconciliationResult]` must not change — same signature, same behavior. `EndToEndSuite`, `InmemoryReconcilerSuite`, and `ManualReconcilerSuite` all call it directly and must keep working unchanged.
- Scope for `reconcileAccountResumable`'s v1: `OneToOne` and `OneToMany` relationships only (the only two implemented today — `ManyToOne`/`ManyToMany` stay unimplemented no-ops either way), and single-attribute rules only (`StringAttributeMatchRule` / `DateAttributeMatchRule`). Composite rules (`AndRule`/`ORRule`) are out of scope for this version — `reconcileAccountResumable` throws a clear `UnsupportedOperationException` if given one, rather than silently mishandling multi-attribute matching. `reconcileAccount` (unchanged) still handles `AndRule` the way it does today for any caller that needs it.
- Backend follows hexagonal architecture; repo methods live behind `domain/port/driven` traits with `adapter/driven` implementations (per `backend/CLAUDE.md`).
- `Test / parallelExecution := false` is already set in `build.sbt` — new tests that commit real data (same as `IngestResumableSuite`) rely on this to avoid the cross-test race already diagnosed and fixed during the ingestion work.
- There is other in-progress, uncommitted work on `main` in `InMemoryReconciler.scala`, `Reconciler.scala`, and `LargeFileIngestionReconciliationSuite.scala` at the time this plan was written (a param rename, a structured-logging call, an `@deprecated` marker, and commented-out/experimental test code). Every step below must preserve that work exactly as found — read the actual current file content before editing rather than assuming it matches any snippet shown here verbatim, and never revert or "clean up" those unrelated changes.

---

### Task 1: `job.progress_json` + checkpoint model

**Files:**
- Modify: `backend/db/schema.sql` (the `recon.job` table)
- Modify: `backend/src/main/scala/perfectpair/domain/model/Job.scala`
- Test: `backend/src/test/scala/perfectpair/adapter/driven/JobRepoLiveSuite.scala` (new file, or add to an existing one if `JobRepoLiveSuite` already exists — check first)

**Interfaces:**
- Produces: `Job.progressJson: Option[String] = None`, mapped to a new nullable `progress_json TEXT` column. Read/written by Task 3's `reconcileAccountResumable`.

- [ ] **Step 1: Check whether a JobRepo test suite already exists**

Run: `find backend/src/test -iname "JobRepo*"`

If `JobRepoLiveSuite.scala` (or similar) already exists, add the new test there following its existing style. If not, create a new one following the pattern used by `backend/src/test/scala/perfectpair/adapter/driven/FileInventoryRepoLiveSuite.scala` (added during the ingestion work) — same `given transactor`/`given dataSource` setup, `rollBackOnly` for isolation.

- [ ] **Step 2: Write the failing test**

```scala
package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.{Job, JobStatus, JobType}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class JobRepoLiveSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  test("progressJson round-trips through the database"):
    val jobRepo = JobRepoLive()
    transact(transactor):
      rollBackOnly:
        val job = Job(
          referenceId = createID(),
          jobType     = JobType.Reconciliation,
          jobStatus   = JobStatus.InProgress
        )
        jobRepo.insert(job)
        assertEquals(jobRepo.findById(job.id).get.progressJson, None)

        jobRepo.update(job.copy(progressJson = Some("""{"foo":"bar"}""")))
        assertEquals(jobRepo.findById(job.id).get.progressJson, Some("""{"foo":"bar"}"""))
```

Add `import perfectpair.domain.model.createID` if `createID` isn't already reachable (check how other tests construct IDs — e.g. `perfectpair.domain.model.createID` per `ReconcilerSuite.scala`'s existing `import perfectpair.domain.model.{Movement, MovementSpec, createID}`).

- [ ] **Step 3: Run test to verify it fails**

Run: `sbt "backend/testOnly perfectpair.adapter.driven.JobRepoLiveSuite"`

Expected: FAIL to compile — `Job` has no `progressJson` parameter yet.

- [ ] **Step 4: Add the column and the field**

In `backend/db/schema.sql`, in the `recon.job` table (currently, per `backend/db/schema.sql:115-124`):

```sql
create table recon.job
(
    id            uuid primary key,
    job_type      text                                  not null,
    job_status    text                                  not null,
    job_info_json text,
    reference_id  uuid                                  not null,
    error_message text,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);
```

add `progress_json text,` right after `job_info_json`:

```sql
create table recon.job
(
    id            uuid primary key,
    job_type      text                                  not null,
    job_status    text                                  not null,
    job_info_json text,
    progress_json text,
    reference_id  uuid                                  not null,
    error_message text,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);
```

Apply directly to the local database (do not `make create-db`, which drops the whole database and can fail if something else holds a connection — this was already hit once during the ingestion work):

```bash
/Library/PostgreSQL/18/bin/psql -p 5432 -Upostgres -d recon_db -c "ALTER TABLE recon.job ADD COLUMN IF NOT EXISTS progress_json TEXT;"
```

In `backend/src/main/scala/perfectpair/domain/model/Job.scala`, replace:

```scala
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Job(
  @Id id:        ID                     = createID(),
  referenceId:   ID,
  jobType:       JobType,
  jobStatus:     JobStatus              = Submitted,
  jobInfoJson:   Option[String]         = None,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec:
```

with:

```scala
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Job(
  @Id id:        ID                     = createID(),
  referenceId:   ID,
  jobType:       JobType,
  jobStatus:     JobStatus              = Submitted,
  jobInfoJson:   Option[String]         = None,
  progressJson:  Option[String]         = None,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec:
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "backend/testOnly perfectpair.adapter.driven.JobRepoLiveSuite"`

Expected: PASS.

- [ ] **Step 6: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS, same count as before plus the new test.

- [ ] **Step 7: Commit**

```bash
git add backend/db/schema.sql \
        backend/src/main/scala/perfectpair/domain/model/Job.scala \
        backend/src/test/scala/perfectpair/adapter/driven/JobRepoLiveSuite.scala
git commit -m "Add progressJson to Job for per-attempt reconciliation checkpoints

Groundwork for resumable reconciliation: a place to persist how far a
specific job attempt got, mirroring how jobInfoJson already stores a
job's final result. Updated incrementally as batches commit (Task 3),
unlike jobInfoJson which is only set once on completion."
```

---

### Task 2: Keyset-paginated unreconciled-movements query

**Files:**
- Modify: `backend/src/main/scala/perfectpair/domain/port/driven/MovementRepo.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driven/MovementRepoLive.scala`
- Test: `backend/src/test/scala/perfectpair/adapter/driven/MovementRepoLiveSuite.scala` (add to the existing suite)

**Interfaces:**
- Produces: `MovementRepo.findUnreconciledPage(movementSpecId: ID, matchKeyExpr: String, afterKey: Option[String], pageSize: Int): Txn[Vector[Movement]]` — returns up to `pageSize` unreconciled movements for `movementSpecId`, ordered by `matchKeyExpr`, with `matchKeyExpr > afterKey` (or `matchKeyExpr > ""` when `afterKey` is `None` — see note below). Used by Task 3.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/scala/perfectpair/adapter/driven/MovementRepoLiveSuite.scala` (after the existing tests, following the file's established style — `DataSetup()` inside `rollBackOnly`, `MovementRepoLive()` constructed fresh per test):

```scala
  test("findUnreconciledPage orders by the match key expression and pages via afterKey"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        for id <- Seq("30", "10", "20")
        do
          movementRepo.insert(
            Movement(
              inAccountId    = data.journalAccount.id,
              outAccountId   = data.reconAccount.id,
              movementSpecId = data.journalToRecon.id,
              amount         = 100,
              properties     = JSONBProps(Map("id" -> id))
            )
          )

        val matchKeyExpr = "a.properties['props']['id']::text"

        val firstPage = movementRepo.findUnreconciledPage(
          movementSpecId = data.journalToRecon.id,
          matchKeyExpr    = matchKeyExpr,
          afterKey        = None,
          pageSize        = 2
        )
        assertEquals(firstPage.map(_.properties.props("id")), Vector("\"10\"", "\"20\""))

        val secondPage = movementRepo.findUnreconciledPage(
          movementSpecId = data.journalToRecon.id,
          matchKeyExpr    = matchKeyExpr,
          afterKey        = Some(firstPage.last.properties.props("id")),
          pageSize        = 2
        )
        assertEquals(secondPage.map(_.properties.props("id")), Vector("\"30\""))
```

Note on the expected values: `properties['props']['id']` extracts the JSONB value with `->` semantics (still JSON, quoted), which is why `::text` on the raw jsonb value yields the quoted form (`"10"`) — matching what `matchKeyExpr` will actually compare on. This mirrors the existing `StringAttributeMatchRule.sql` behavior (`inKey.modifySQL` calls `+ "::text"` too), so it's consistent with how the rest of the codebase already treats these values, not a new quirk introduced here.

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly perfectpair.adapter.driven.MovementRepoLiveSuite"`

Expected: FAIL to compile — `findUnreconciledPage` doesn't exist yet.

- [ ] **Step 3: Add the method**

In `backend/src/main/scala/perfectpair/domain/port/driven/MovementRepo.scala`, add to the `MovementRepo` trait (after `findEqualityMatches`):

```scala
  def findUnreconciledPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  ): Txn[Vector[Movement]]
```

In `backend/src/main/scala/perfectpair/adapter/driven/MovementRepoLive.scala`, add (after `findEqualityMatches`):

```scala
  def findUnreconciledPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  ): Txn[Vector[Movement]] =
    val keyExpr = SqlLiteral(matchKeyExpr)
    // No lower bound needed on the first page: match keys come from real business
    // identifiers and are never empty strings in practice, so "> ''" is equivalent
    // to "no filter" while still letting afterKey be a plain, safely-parameterized
    // bind value rather than a conditionally-included raw SQL fragment.
    val after = afterKey.getOrElse("")
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE reconciliation_id IS NULL
      AND movement_spec_id = $movementSpecId
      AND $keyExpr > $after
      ORDER BY $keyExpr
      LIMIT $pageSize
    """.query[Movement].run()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "backend/testOnly perfectpair.adapter.driven.MovementRepoLiveSuite"`

Expected: PASS.

- [ ] **Step 5: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/port/driven/MovementRepo.scala \
        backend/src/main/scala/perfectpair/adapter/driven/MovementRepoLive.scala \
        backend/src/test/scala/perfectpair/adapter/driven/MovementRepoLiveSuite.scala
git commit -m "Add MovementRepo.findUnreconciledPage for keyset-paginated matching

Orders unreconciled movements by a caller-supplied match-key SQL
expression and pages via afterKey, so reconciliation can stream both
sides of a match instead of loading every unreconciled movement into
memory and sorting it in the JVM."
```

---

### Task 3: `reconcileAccountResumable` — streaming `OneToOne`

**Files:**
- Modify: `backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala`
- Modify: `backend/src/main/scala/perfectpair/app/Main.scala:26-44` (add `given Transactor` if not already present — check first, since `Main.scala` already gained one during the ingestion work; only add if missing)
- Test: `backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala` (new file)

**Interfaces:**
- Consumes: `MovementRepo.findUnreconciledPage` (Task 2), `Job.progressJson` (Task 1), `BatchDecision` (already defined in `Ingestor.scala`, same `perfectpair.domain.service` package — reused, not redefined).
- Produces:
  - `case class ReconciliationCheckpoint(lastInMatchKey: Option[String] = None, lastOutMatchKey: Option[String] = None)`
  - `case class SpecBatchProgress(specId: ID, matchesInBatch: Int, checkpoint: ReconciliationCheckpoint)`
  - `case class ReconciliationResumableResult(reconciliationSpecInsertCount: Map[ID, Int], processingTimeSeconds: Long, completed: Boolean)`
  - `InMemoryReconciler.reconcileAccountResumable(accountId: ID, batchSize: Int = 500, jobId: Option[ID] = None, onBatchComplete: SpecBatchProgress => BatchDecision = _ => BatchDecision.Commit): ReconciliationResumableResult` — handles `OneToOne` in this task; `OneToMany` is added in Task 4 (until then it falls through to a `???`-style not-yet-implemented branch, matching the existing code's own incompleteness rather than silently doing nothing).

Read the **actual current content** of `InMemoryReconciler.scala` before editing (per Global Constraints — it has an uncommitted param rename and a logging call not shown in the snippets below).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala`:

```scala
package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.{StringAttribute, StringAttributeMatchRule}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*

import javax.sql.DataSource

class ReconcileAccountResumableSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given reconSpecRepo: ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()

  given jobRepo: JobRepo = JobRepoLive()

  given ledger: Ledger = Ledger()

  private def teardown(data: DataSetup): Unit =
    transact(transactor):
      data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  private def insertMovement(data: DataSetup, spec: MovementSpec, idValue: String): Movement =
    val m = Movement(
      inAccountId    = spec.inAccountId,
      outAccountId   = spec.outAccountId,
      movementSpecId = spec.id,
      amount         = 100,
      properties     = JSONBProps(Map("id" -> idValue))
    )
    data.movementRepo.insert(m)
    m

  test("matches the same pairs as reconcileAccount for OneToOne"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        for id <- Seq("3", "1", "2")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = InMemoryReconciler()
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 3)

      val movements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assert(movements.forall(_.reconciliationId.nonEmpty), "every movement should be reconciled")
    finally teardown(data)

  test("earlier batches commit durably even when a later batch is rolled back"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        for id <- Seq("1", "2", "3", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = InMemoryReconciler()
      val result = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize       = 1,
        onBatchComplete = progress => if progress.checkpoint.lastInMatchKey.contains("3") then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 2, "only ids 1 and 2 should have committed")

      val reconciledCount = transact(transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).count(_.reconciliationId.nonEmpty)
      assertEquals(reconciledCount, 4, "2 committed matches x 2 movements each")
    finally teardown(data)

  test("resuming from a job's progressJson picks up where it left off, including a late arrival with a smaller key"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        for id <- Seq("1", "2", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val job = transact(transactor):
        val j = Job(referenceId = data.reconAccount.id, jobType = JobType.Reconciliation, jobStatus = JobStatus.InProgress)
        jobRepo.insert(j)
        j

      val reconciler = InMemoryReconciler()
      val first = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize       = 1,
        jobId           = Some(job.id),
        onBatchComplete = progress => if progress.checkpoint.lastInMatchKey.contains("2") then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)
      assertEquals(first.reconciliationSpecInsertCount(data.reconSpec.id), 1, "only id 1 committed")

      // Late arrival: id "3" sorts before the checkpoint ("1"), proving a fresh trigger still finds it.
      transact(transactor):
        insertMovement(data, data.journalToRecon, "3")
        insertMovement(data, data.reportToRecon, "3")

      val second = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10, jobId = Some(job.id))
      assertEquals(second.completed, true)
      assertEquals(second.reconciliationSpecInsertCount(data.reconSpec.id), 3, "ids 2, 3, and 4")

      val allReconciled = transact(transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).forall(_.reconciliationId.nonEmpty)
      assert(allReconciled)
    finally
      transact(transactor)(jobRepo.deleteById(job.id))
      teardown(data)
```

Note: `data.reconSpec.rule()` (set up by `DataSetup`, per `backend/src/test/scala/perfectpair/domain/DataSetup.scala`) is already a `StringAttributeMatchRule(inKey = StringAttribute(key = "id"), outKey = StringAttribute(key = "id"))` with `relationship = OneToOne` (the default) — no changes needed to `DataSetup` for this task.

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReconcileAccountResumableSuite"`

Expected: FAIL to compile — `reconcileAccountResumable`, `ReconciliationCheckpoint`, and `SpecBatchProgress` don't exist yet.

- [ ] **Step 3: Confirm (or add) `given Transactor` in `Main.scala`**

Run: `grep -n "given transactor" backend/src/main/scala/perfectpair/app/Main.scala`

If it's already there (added during the ingestion work), skip this step. If not, add `given transactor: Transactor = Transactor(dataSource = given_DataSource)` right after the `given DataSource = ...` line, same as was done for `Ingestor`.

- [ ] **Step 4: Add the new types and `Transactor` dependency**

In `backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala`, add the import for `Transactor`/`transact`. Find the current import block (it will include the uncommitted `com.typesafe.scalalogging.Logger` / `net.logstash.logback.argument.StructuredArguments.kv` lines) and add:

```scala
import com.augustnagro.magnum.{Transactor, transact}
```

Add to the `ReconciliationResult` case class's companion area (after `ReconciliationResult`, before `class InMemoryReconciler`):

```scala
case class ReconciliationCheckpoint(
  lastInMatchKey:  Option[String] = None,
  lastOutMatchKey: Option[String] = None
) derives upickle.default.ReadWriter

case class SpecBatchProgress(specId: ID, matchesInBatch: Int, checkpoint: ReconciliationCheckpoint)

case class ReconciliationResumableResult(
  reconciliationSpecInsertCount: Map[ID, Int],
  processingTimeSeconds:         Long,
  completed:                     Boolean
):
  def totalCount: Int = reconciliationSpecInsertCount.values.sum

private case object ReconciliationStopped extends RuntimeException
```

Add `Transactor` to `InMemoryReconciler`'s constructor. Find the current `using` clause (it will still have the original 8 parameters — `ledger`, `movementRepo`, `reconSpecRepo`, `movementSpecRepo`, `reconRepo`, `fundFlowConfigRepo`, `eventStore`, `clock`) and add `val transactor: Transactor` and `val jobRepo: JobRepo` to it:

```scala
class InMemoryReconciler(using
  val ledger:             Ledger,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo,
  val eventStore:         EventStore,
  val clock:              ClockService,
  val transactor:         Transactor,
  val jobRepo:            JobRepo
):
```

`JobRepo` is needed because `reconcileAccountResumable` reads/writes `Job.progressJson` itself, the same way `Ingestor` reads/writes `FileInventory` directly via its own `fileInventoryRepo` dependency.

- [ ] **Step 5: Check every `InMemoryReconciler()` construction site has both new givens in scope**

Run: `grep -rn "InMemoryReconciler()" backend/src --include="*.scala"`

For each file found (`EndToEndSuite.scala`, `InmemoryReconcilerSuite.scala`, `ManualReconcilerSuite.scala`, `Main.scala`), run `grep -n "given transactor\|given jobRepo" <file>`. All four already declare `given transactor: Transactor` (confirmed during planning). Check `given jobRepo: JobRepo` specifically — if any file is missing it, add `given jobRepo: JobRepo = JobRepoLive()` near its other given declarations (matching the existing style in that file).

- [ ] **Step 6: Add the match-key helpers and `reconcileAccountResumable`**

In `backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala`, add these private helpers (near `compareMovements`, since they serve the same purpose for the streaming path):

```scala
  private def matchKeyAttribute(rule: Rule, direction: Direction): StringAttribute | DateAttribute =
    rule match
      case s: StringAttributeMatchRule => if direction == IN then s.inKey else s.outKey
      case d: DateAttributeMatchRule   => if direction == IN then d.inKey else d.outKey
      case other =>
        throw UnsupportedOperationException(
          s"reconcileAccountResumable only supports single-attribute rules (StringAttributeMatchRule/DateAttributeMatchRule); got ${other.getClass.getSimpleName}"
        )

  private def matchKeyExpr(rule: Rule, direction: Direction, tableAlias: String): String =
    matchKeyAttribute(rule, direction) match
      case s: StringAttribute => s.modifySQL(s"$tableAlias.properties['props']['${s.key}']")
      case d: DateAttribute   => d.modifySQL(s"$tableAlias.properties['props']['${d.key}']")

  private def matchKeyValue(rule: Rule, direction: Direction, movement: Movement): String =
    matchKeyAttribute(rule, direction) match
      case s: StringAttribute => s.getValue(movement.props)
      case d: DateAttribute   => d.getValue(movement.props).toString
```

Add `reconcileAccountResumable` as a new method, right after `reconcileAccount`:

```scala
  def reconcileAccountResumable(
    accountId:       ID,
    batchSize:       Int                                = 500,
    jobId:           Option[ID]                          = None,
    onBatchComplete: SpecBatchProgress => BatchDecision  = _ => BatchDecision.Commit
  ): ReconciliationResumableResult =
    val beginTime = Instant.now()

    val startingCheckpoints: Map[ID, ReconciliationCheckpoint] =
      jobId
        .flatMap(id => transact(transactor)(jobRepo.findById(id)))
        .flatMap(_.progressJson)
        .map(upickle.read[Map[ID, ReconciliationCheckpoint]](_))
        .getOrElse(Map.empty)

    val reconSpecs = transact(transactor)(reconSpecRepo.findByAccountID(accountId))

    var specCounts    = Map.empty[ID, Int]
    var checkpoints   = startingCheckpoints
    var completed     = true

    try
      for reconSpec <- reconSpecs do
        val startCheckpoint = checkpoints.getOrElse(reconSpec.id, ReconciliationCheckpoint())
        reconSpec.relationship match
          case OneToOne =>
            val (matchCount, endingCheckpoint) =
              reconcileOneToOneStreaming(reconSpec, accountId, startCheckpoint, batchSize, jobId, checkpoints, onBatchComplete)
            specCounts += (reconSpec.id  -> matchCount)
            checkpoints += (reconSpec.id -> endingCheckpoint)
          case OneToMany =>
            throw UnsupportedOperationException("reconcileAccountResumable does not support OneToMany yet")
          case ManyToOne | ManyToMany =>
            specCounts += (reconSpec.id -> 0)
    catch
      case ReconciliationStopped =>
        completed = false

    if specCounts.values.sum > 0 then
      transact(transactor)(eventStore.publishEvent(AccountReconciled(accountID = accountId)))

    ReconciliationResumableResult(
      reconciliationSpecInsertCount = specCounts,
      processingTimeSeconds         = Instant.now().getEpochSecond - beginTime.getEpochSecond,
      completed                     = completed
    )
```

And the streaming `OneToOne` implementation itself:

```scala
  private val pageSize = 500

  private def fetchPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String]
  ): Vector[Movement] =
    transact(transactor):
      movementRepo.findUnreconciledPage(movementSpecId, matchKeyExpr, afterKey, pageSize)

  private def reconcileOneToOneStreaming(
    reconSpec:        ReconciliationSpec,
    accountId:        ID,
    startCheckpoint:  ReconciliationCheckpoint,
    batchSize:        Int,
    jobId:            Option[ID],
    allCheckpoints:   Map[ID, ReconciliationCheckpoint],
    onBatchComplete:  SpecBatchProgress => BatchDecision
  ): (Int, ReconciliationCheckpoint) =
    val rule    = reconSpec.rule()
    val inExpr  = matchKeyExpr(rule, IN, "a")
    val outExpr = matchKeyExpr(rule, OUT, "a")

    var inPage       = fetchPage(reconSpec.inMovementSpecId, inExpr, startCheckpoint.lastInMatchKey)
    var outPage      = fetchPage(reconSpec.outMovementSpecId, outExpr, startCheckpoint.lastOutMatchKey)
    var inIdx        = 0
    var outIdx       = 0
    var lastInKey     = startCheckpoint.lastInMatchKey
    var lastOutKey    = startCheckpoint.lastOutMatchKey
    var totalMatches  = 0

    val pendingMatches = ListBuffer[(Movement, Movement)]()

    def ensureIn(): Boolean =
      if inIdx < inPage.size then true
      else if inPage.size < pageSize then false
      else
        inPage = fetchPage(reconSpec.inMovementSpecId, inExpr, lastInKey)
        inIdx = 0
        inPage.nonEmpty

    def ensureOut(): Boolean =
      if outIdx < outPage.size then true
      else if outPage.size < pageSize then false
      else
        outPage = fetchPage(reconSpec.outMovementSpecId, outExpr, lastOutKey)
        outIdx = 0
        outPage.nonEmpty

    def commitBatch(): Unit =
      val matches = pendingMatches.toVector
      pendingMatches.clear()

      val checkpoint = ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey)

      val decision = transact(transactor):
        val now = clock.now
        val reconciliations = matches.map: _ =>
          Reconciliation(specId = reconSpec.id, appliedRules = rule.sql, createdAt = Some(now), updatedAt = Some(now))
        reconRepo.insertAll(reconciliations)

        var batchDelta: Money = Zero
        val updatedMovements = matches.zip(reconciliations).flatMap: case ((inMov, outMov), recon) =>
          inMov.reconciliationId = Some(recon.id)
          outMov.reconciliationId = Some(recon.id)
          batchDelta = batchDelta + inMov.signedAmountFor(accountId) + outMov.signedAmountFor(accountId)
          Seq(inMov, outMov)
        movementRepo.updateAll(updatedMovements)

        // One markReconciled call for the whole batch's net delta, rather than one per match —
        // ledger.reconcile (unchanged, still used by reconcileAccount) does the equivalent sum
        // internally per reconciliation; here the sum spans every reconciliation in the batch.
        ledger.markReconciled(accountId, batchDelta)

        jobId.foreach: id =>
          val job = jobRepo.findById(id).ErrIfNotFound("Job", id)
          val updatedCheckpoints = allCheckpoints + (reconSpec.id -> checkpoint)
          jobRepo.update(job.copy(progressJson = Some(upickle.write(updatedCheckpoints))))

        val progress       = SpecBatchProgress(reconSpec.id, matches.size, checkpoint)
        val batchDecision  = onBatchComplete(progress)
        if batchDecision == BatchDecision.Rollback then summon[com.augustnagro.magnum.DbTx].connection.rollback()
        batchDecision

      decision match
        case BatchDecision.Commit =>
          totalMatches += matches.size
        case BatchDecision.Rollback =>
          throw ReconciliationStopped

    while ensureIn() && ensureOut() do
      val inMovement  = inPage(inIdx)
      val outMovement = outPage(outIdx)
      val inKey       = matchKeyValue(rule, IN, inMovement)
      val outKey      = matchKeyValue(rule, OUT, outMovement)
      val comparison  = inKey.compareTo(outKey)

      comparison match
        case 0 =>
          pendingMatches += (inMovement -> outMovement)
          lastInKey = Some(inKey)
          lastOutKey = Some(outKey)
          inIdx += 1
          outIdx += 1
          if pendingMatches.size >= batchSize then commitBatch()
        case x if x < 0 =>
          lastInKey = Some(inKey)
          inIdx += 1
        case _ =>
          lastOutKey = Some(outKey)
          outIdx += 1

    if pendingMatches.nonEmpty then commitBatch()

    (totalMatches, ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey))
```

This mirrors the existing `OneToOne` branch of `reconcileAccount` (`InMemoryReconciler.scala:54-79`) exactly in matching logic — same two-pointer advance rules — but reads pages on demand instead of the full sorted sequence, and commits in bounded batches instead of one transaction for everything. `ledger.markReconciled` (not `ledger.reconcile`, which loops individual `movementRepo.update` calls) is used directly here since the batch already did the bulk `movementRepo.updateAll` itself; `markReconciled` just needs the net delta for the account, which is `Movement.signedAmountFor(accountId)` summed over both sides of each match — check `Ledger.scala` for `signedAmountFor`'s exact signature before wiring this in, and confirm this matches what `Ledger.reconcile` computes today so the accounting stays identical.

- [ ] **Step 7: Run tests to verify they pass**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReconcileAccountResumableSuite"`

Expected: PASS.

- [ ] **Step 8: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala \
        backend/src/main/scala/perfectpair/app/Main.scala \
        backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala
git commit -m "Add InMemoryReconciler.reconcileAccountResumable for OneToOne

reconcileAccount stays untouched. reconcileAccountResumable streams both
sides of a match via keyset pagination instead of loading every
unreconciled movement into memory, and commits in bounded batches with
a per-job-attempt checkpoint (job.progress_json) instead of one
transaction for the whole account. The checkpoint only ever resumes the
same crashed attempt — a fresh trigger always re-derives its backlog
from reconciliation_id IS NULL with no lower bound, so late-arriving
movements are never permanently skipped. OneToMany is not yet supported
(Task 4)."
```

---

### Task 4: Extend `reconcileAccountResumable` to `OneToMany`

**Files:**
- Modify: `backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala`
- Test: `backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala`

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: `reconcileAccountResumable` now handles `OneToMany` in addition to `OneToOne`.

`OneToMany`'s existing behavior (`InMemoryReconciler.scala:81-142`) has two phases: complete any `InProgress` reconciliations from a *previous* run first (a partial group waiting for more out-movements), then group newly-streamed out-movements against in-movements. Read the actual current code for both phases before implementing — the exact field names and balance-accumulation logic must match what's there today so the streaming version produces identical results, not just "close enough."

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala`. These need a `OneToMany` `ReconciliationSpec` — `DataSetup` doesn't provide one by default, so build it inline the same way `DataSetup` builds its own (`backend/src/test/scala/perfectpair/domain/DataSetup.scala:57-91`), or add a small local helper in the test file that inserts a second `ReconciliationSpec` with `relationship = OneToMany` on `data.reconAccount`, reusing `data.journalToRecon`/`data.reportToRecon` as the in/out specs. Check `ReconciliationSpec`'s constructor (`backend/src/main/scala/perfectpair/domain/model/ReconciliationSpec.scala`) for the exact fields required.

```scala
  private def oneToManySpec(data: DataSetup): ReconciliationSpec =
    val rule = StringAttributeMatchRule(inKey = StringAttribute(key = "groupId"), outKey = StringAttribute(key = "groupId"))
    val spec = ReconciliationSpec(
      accountId         = data.reconAccount.id,
      relationship      = Relationship.OneToMany,
      ruleJson          = upickle.write(rule),
      inMovementSpecId  = data.journalToRecon.id,
      outMovementSpecId = data.reportToRecon.id
    )
    transact(transactor)(data.reconSpecRepo.insert(spec))
    spec

  test("OneToMany groups multiple out-movements against one in-movement, including a group spanning a page boundary"):
    val data = transact(transactor)(DataSetup())
    val spec = oneToManySpec(data)
    try
      transact(transactor):
        // one in-movement worth 100, matched by three out-movements (40 + 40 + 20) all sharing groupId "g1"
        data.movementRepo.insert(
          Movement(
            inAccountId = spec.inAccountId, outAccountId = spec.outAccountId, movementSpecId = data.journalToRecon.id,
            amount = 100, properties = JSONBProps(Map("groupId" -> "g1"))
          )
        )
        for amount <- Seq(BigDecimal(40), BigDecimal(40), BigDecimal(20))
        do
          data.movementRepo.insert(
            Movement(
              inAccountId = data.reportAccount.id, outAccountId = spec.outAccountId, movementSpecId = data.reportToRecon.id,
              amount = amount, properties = JSONBProps(Map("groupId" -> "g1"))
            )
          )

      val reconciler = InMemoryReconciler()
      // pageSize forced small enough that the 3-row out-group can't fit in one page fetch,
      // proving the peek-ahead logic correctly extends the group across a page boundary.
      val result = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "one reconciliation covering the whole group")

      val reconciliations = transact(transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))
    finally
      transact(transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReconcileAccountResumableSuite"`

Expected: FAIL — `reconcileAccountResumable` currently throws `UnsupportedOperationException` for `OneToMany`.

- [ ] **Step 3: Implement streaming `OneToMany`**

Replace the `case OneToMany => throw UnsupportedOperationException(...)` branch added in Task 3 with a real implementation. This is genuinely the most intricate part of this plan — take the time to get it right rather than approximating it:

1. **Phase 1** (existing `InProgress` reconciliations): read via `reconRepo.findInProgressBySpecId(reconSpec.id)` exactly as `reconcileAccount` does today (this list is bounded by however many groups are mid-completion, not by the size of the backlog, so it doesn't need streaming). For each, stream-match against the out-side (via `findUnreconciledPage`, same as `OneToOne`) instead of filtering a fully-materialized `remainingOutMovements` buffer.
2. **Phase 2** (new groups): stream the in-side and out-side via keyset pagination exactly like `OneToOne`, but instead of matching 1:1, group *consecutive* out-rows that share the same match key against the current in-row. The existing code's inner `while outIndex < outMovements.size && compareMovements(...) == 0` loop (`InMemoryReconciler.scala:114-116`) becomes: keep pulling from `ensureOut()` (which itself fetches the next page on demand) as long as the next out-row's key still equals the current in-row's key — this is exactly where the page-boundary case gets exercised naturally, since `ensureOut()` already fetches a fresh page transparently when the current one is exhausted.
3. Batch commits the same way as `OneToOne` — accumulate completed groups, commit every `batchSize` groups (or matches — pick whichever the test asserts on, and be consistent with `SpecBatchProgress.matchesInBatch`'s meaning for `OneToOne`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "backend/testOnly perfectpair.domain.service.ReconcileAccountResumableSuite"`

Expected: PASS (all tests from Task 3 and Task 4).

- [ ] **Step 5: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala \
        backend/src/test/scala/perfectpair/domain/service/ReconcileAccountResumableSuite.scala
git commit -m "Extend reconcileAccountResumable to OneToMany

Streams both the existing-InProgress-reconciliation completion phase
and the new-group phase, replacing the in-memory remainingOutMovements
buffer with keyset pagination. Groups of consecutive same-key
out-movements are correctly extended across a page boundary since
fetching the next page happens transparently inside the same pull
that's already checking whether the group continues."
```

---

### Task 5: Wire `Main.scala` and `Routes.scala` to the new method

**Files:**
- Modify: `backend/src/main/scala/perfectpair/app/Main.scala:55-81`
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala:202-216`

**Interfaces:**
- Consumes: `reconcileAccountResumable` (Tasks 3-4).

- [ ] **Step 1: Update `Main.scala`'s Reconciliation job fork**

Read the current content of `Main.scala:55-81` first (it may have shifted slightly from earlier line numbers depending on what Task 3's Step 3 did). Currently:

```scala
      fork:
        try
          jobManager.pollForJob(JobType.Reconciliation, 5) { job =>
            try
              transact(Transactor(dataSource = given_DataSource)):
                val account =
                  accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

                val reconcilationResult = reconciler.reconcileAccount(account.id)

                val jobInfo = ReconciliationInfo(
                  accountId                   = account.id.toString,
                  accountName                 = account.name,
                  reconciliationsCreatedCount = reconcilationResult.totalCount,
                  processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                )
                jobManager.completeJob(job.id, jobInfo)
            catch
              case ex: Exception =>
                ex.printStackTrace()
                throw ex
          }
        catch
          case ex: Exception =>
            logger.error("failed poll for Reconciliation job", ex)
```

Replace with:

```scala
      fork:
        try
          jobManager.pollForJob(JobType.Reconciliation, 5) { job =>
            try
              val account = transact(Transactor(dataSource = given_DataSource)):
                accountRepo
                  .findById(job.referenceId)
                  .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

              val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

              transact(Transactor(dataSource = given_DataSource)):
                val jobInfo = ReconciliationInfo(
                  accountId                   = account.id.toString,
                  accountName                 = account.name,
                  reconciliationsCreatedCount = reconcilationResult.totalCount,
                  processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                )
                jobManager.completeJob(job.id, jobInfo)
            catch
              case ex: Exception =>
                ex.printStackTrace()
                throw ex
          }
        catch
          case ex: Exception =>
            logger.error("failed poll for Reconciliation job", ex)
```

`reconciler.reconcileAccountResumable(...)` now runs outside any ambient transaction (it owns its own, same as `reconcileAccount` used to run inside one) — the account lookup and the job-completion update each get their own short transaction around them instead.

- [ ] **Step 2: Update `Routes.scala`'s manual reconcile endpoint**

Currently (`Routes.scala:202-216`):

```scala
  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reoncileAccount(id: String, request: cask.Request): String =
    val accountId = UUID.fromString(id)
    transact(transactor):
      val account = accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $id not found"))
      jobManager.submitAndProcessJob(JobType.Reconciliation, accountId) { job =>
        val reconcilationResult = reconciler.reconcileAccount(account.id)
        ReconciliationInfo(
          accountId                   = account.id.toString,
          accountName                 = account.name,
          reconciliationsCreatedCount = reconcilationResult.totalCount,
          processingTimeSeconds       = reconcilationResult.processingTimeSeconds
        )
      }
    write("reconciled")
```

`jobManager.submitAndProcessJob` (`JobManager.scala:17-35`) is itself `Txn[Unit]` — it submits the job, marks it `InProgress`, runs `processFn`, and completes/errors it, all expecting to run inside one ambient transaction today. Since `reconcileAccountResumable` can't run inside that transaction, this route needs restructuring: submit and mark the job `InProgress` in one short transaction, call `reconcileAccountResumable` outside any transaction, then complete the job in another short transaction — essentially inlining what `submitAndProcessJob` does today rather than calling it, since that helper's contract (run `processFn` inside the same transaction as job bookkeeping) is exactly what no longer holds. Replace with:

```scala
  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reoncileAccount(id: String, request: cask.Request): String =
    val accountId = UUID.fromString(id)
    val (account, job) = transact(transactor):
      val account = accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $id not found"))
      val jobId   = jobManager.submitJob(JobType.Reconciliation, accountId)
      val job     = jobRepo.findById(jobId).ErrIfNotFound("Job", jobId)
      jobRepo.update(job.copy(jobStatus = JobStatus.InProgress))
      (account, job)

    val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

    transact(transactor):
      val jobInfo = ReconciliationInfo(
        accountId                   = account.id.toString,
        accountName                 = account.name,
        reconciliationsCreatedCount = reconcilationResult.totalCount,
        processingTimeSeconds       = reconcilationResult.processingTimeSeconds
      )
      jobManager.completeJob(job.id, jobInfo)
    write("reconciled")
```

Check `JobManager.submitJob`'s exact signature (`JobManager.scala:38-45`) before wiring this in — confirm it returns the job's `ID` as shown above, and that `JobStatus`/`JobType` are already in scope in `Routes.scala` (they should be, via the existing `import perfectpair.domain.model.*`).

- [ ] **Step 3: Run the full backend suite**

Run: `sbt backend/test`

Expected: PASS. (Note: this doesn't exercise `Main.scala`'s job-poller loop or the HTTP route directly — those aren't covered by the automated test suite today either, before this change — so this step confirms no compilation/regression issues, not new end-to-end coverage of these two call sites.)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/scala/perfectpair/app/Main.scala \
        backend/src/main/scala/perfectpair/adapter/driving/Routes.scala
git commit -m "Point production reconciliation call sites at reconcileAccountResumable

Main.scala's async job poller and Routes.scala's manual HTTP trigger
both switch from reconcileAccount to reconcileAccountResumable, and
stop wrapping it in their own ambient transaction since it now owns its
own. Routes.scala's manual endpoint inlines what submitAndProcessJob
did (submit, mark InProgress, run, complete) since that helper's
single-transaction contract no longer holds once the work inside it
manages its own transactions."
```
