# Batched, Resumable Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `Ingestor` from holding one database transaction open for an entire ingested file (currently up to hours for a 5,000,000-row file), by adding a new `ingestResumable` method that commits in batches, persists a resumable checkpoint, and can pick up correctly after a crash — without changing the existing `ingest` method or breaking any of its current callers/tests.

**Architecture:** `ingest` stays exactly as it is today (ambient-transaction, single method, unchanged idempotency-key formula). A new method, `ingestResumable`, is added alongside it: it owns its own `Transactor` and commits one real transaction per batch, persisting a raw-line-index checkpoint on `FileInventory` in the same transaction as the batch's data changes. `ingestFileFromInventory` (the production job-driven path) switches to calling `ingestResumable`, computing its starting point from the persisted checkpoint. A test-only `onBatchComplete` callback lets tests simulate a crash at a chosen batch and verify the resume behavior.

**Tech Stack:** Scala 3, Magnum (SQL/transactions), PostgreSQL, munit, `ox.flow.Flow`.

## Global Constraints

- `ingest(movementSpec, fileSourceEntry, is): Txn[IngestionResult]` must not change — same signature, same ambient-transaction behavior, same idempotency-key formula. No existing test that calls it may need to change.
- Backend follows hexagonal architecture; repo methods live behind `domain/port/driven` traits with `adapter/driven` implementations (per `backend/CLAUDE.md`).
- Tests use munit with `rollBackOnly` for DB isolation *where applicable* — but any test that exercises `ingestResumable` cannot rely on `rollBackOnly`, since its batches commit on their own connection regardless of what an outer transaction does. Those tests use explicit teardown instead (see Task 2/3/4).
- No Flyway or other migration runner is wired up in this app; schema changes are made directly in `backend/db/schema.sql`, applied locally via `make create-db` / `make restore-db`.
- There is an uncommitted, unstaged edit already present on `backend/src/main/scala/onparity/domain/service/Ingestor.scala` (adds a debug `Logger` and changes `ingest`'s batch size from `100` to `1000`) — this is the user's own in-progress work and is being kept as the current baseline for `ingest`. Every step below that touches `Ingestor.scala` must preserve it exactly; do not revert it, and do not let an IDE/formatter "clean it up."

---

### Task 1: `FileInventory` gains a checkpoint field and a `PROCESSING` status

**Files:**
- Modify: `backend/db/schema.sql:89-102` (the `recon.file_inventory` table)
- Modify: `backend/src/main/scala/onparity/domain/model/FileInventory.scala`
- Test: `backend/src/test/scala/onparity/adapter/driven/FileInventoryRepoLiveSuite.scala` (new file)

**Interfaces:**
- Produces: `FileInventoryStatus` gains a `PROCESSING` case (`QUEUED, PROCESSING, COMPLETED`). `FileInventory` gains `lastProcessedLine: Option[Long] = None`. Both are read/written by Task 2's `ingestResumable`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/scala/onparity/adapter/driven/FileInventoryRepoLiveSuite.scala`:

```scala
package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.domain.DataSetup
import onparity.domain.model.{FileInventory, FileInventoryStatus, InMemoryFileSourceEntry}
import onparity.testing.rollBackOnly

import java.time.OffsetDateTime
import javax.sql.DataSource

class FileInventoryRepoLiveSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  test("lastProcessedLine and PROCESSING status round-trip through the database"):
    val fileInventoryRepo = FileInventoryRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val fileInventory = FileInventory(
          movementSpecId      = data.journalToRecon.id,
          sourceSystem        = "S3",
          fileSourceEntryJson = upickle.write(InMemoryFileSourceEntry("test-file")),
          fileSizeBytes       = 100L,
          lastModifiedAt      = OffsetDateTime.now(),
          status              = FileInventoryStatus.QUEUED,
          lastProcessedAt     = None,
          updatedAt           = None
        )
        fileInventoryRepo.insert(fileInventory)

        fileInventoryRepo.update(
          fileInventory.copy(
            status            = FileInventoryStatus.PROCESSING,
            lastProcessedLine = Some(249L)
          )
        )

        val found = fileInventoryRepo.findById(fileInventory.id).get
        assertEquals(found.status, FileInventoryStatus.PROCESSING)
        assertEquals(found.lastProcessedLine, Some(249L))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly onparity.adapter.driven.FileInventoryRepoLiveSuite"`

Expected: FAIL to compile — `FileInventoryStatus.PROCESSING` doesn't exist yet, and `FileInventory` has no `lastProcessedLine` parameter.

- [ ] **Step 3: Add the column and the fields**

In `backend/db/schema.sql`, in the `recon.file_inventory` table (currently):

```sql
CREATE TABLE recon.file_inventory
(
    id                     UUID PRIMARY KEY,
    movement_spec_id       UUID                     NOT NULL,
    source_system          VARCHAR(50)              NOT NULL,
    file_source_entry_json TEXT                     NOT NULL,
    file_size_bytes        BIGINT                   NOT NULL,
    last_modified_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    status                 TEXT                     NOT NULL DEFAULT 'QUEUED',
    last_processed_at      TIMESTAMP WITH TIME ZONE,
    updated_at             TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_source_path UNIQUE (source_system, file_source_entry_json)
);
```

add `last_processed_line BIGINT,` right after `last_processed_at`:

```sql
CREATE TABLE recon.file_inventory
(
    id                     UUID PRIMARY KEY,
    movement_spec_id       UUID                     NOT NULL,
    source_system          VARCHAR(50)              NOT NULL,
    file_source_entry_json TEXT                     NOT NULL,
    file_size_bytes        BIGINT                   NOT NULL,
    last_modified_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    status                 TEXT                     NOT NULL DEFAULT 'QUEUED',
    last_processed_at      TIMESTAMP WITH TIME ZONE,
    last_processed_line    BIGINT,
    updated_at             TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_source_path UNIQUE (source_system, file_source_entry_json)
);
```

`status` is a plain `TEXT` column (not a native Postgres enum), so no separate migration is needed for the new `PROCESSING` value.

In `backend/src/main/scala/onparity/domain/model/FileInventory.scala`, replace:

```scala
enum FileInventoryStatus derives DbCodec:
  case QUEUED, COMPLETED

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FileInventory(
  @Id id:              ID = createID(),
  movementSpecId:      ID,
  sourceSystem:        String,
  fileSourceEntryJson: String,
  fileSizeBytes:       Long,
  lastModifiedAt:      OffsetDateTime,
  status:              FileInventoryStatus,
  lastProcessedAt:     Option[OffsetDateTime],
  updatedAt:           Option[OffsetDateTime]
) derives DbCodec:
```

with:

```scala
enum FileInventoryStatus derives DbCodec:
  case QUEUED, PROCESSING, COMPLETED

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FileInventory(
  @Id id:              ID = createID(),
  movementSpecId:      ID,
  sourceSystem:        String,
  fileSourceEntryJson: String,
  fileSizeBytes:       Long,
  lastModifiedAt:      OffsetDateTime,
  status:              FileInventoryStatus,
  lastProcessedAt:     Option[OffsetDateTime],
  lastProcessedLine:   Option[Long] = None,
  updatedAt:           Option[OffsetDateTime]
) derives DbCodec:
```

Then apply the updated schema to the local database: `make create-db` (or `make restore-db` if you want to keep existing data — `create-db` drops and recreates, which is fine for a local dev/test database).

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "backend/testOnly onparity.adapter.driven.FileInventoryRepoLiveSuite"`

Expected: PASS.

- [ ] **Step 5: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS (same pass count as before, plus the one new test; `FileInventory` construction sites at `Ingestor.scala:114` and `IngestorSuite.scala:52` both use named arguments exclusively, so the new defaulted field doesn't break them).

- [ ] **Step 6: Commit**

```bash
git add backend/db/schema.sql \
        backend/src/main/scala/onparity/domain/model/FileInventory.scala \
        backend/src/test/scala/onparity/adapter/driven/FileInventoryRepoLiveSuite.scala
git commit -m "Add lastProcessedLine checkpoint and PROCESSING status to FileInventory

Groundwork for resumable ingestion: a place to persist how far a file
has been processed, and a status distinct from QUEUED/COMPLETED for
'partially processed, resumable'."
```

---

### Task 2: `Ingestor.ingestResumable` — batched commits, checkpoint, resume callback

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/service/Ingestor.scala`
- Modify: `backend/src/main/scala/onparity/app/Main.scala:26-44` (add a `given Transactor` so `Ingestor()`'s new dependency resolves)
- Test: `backend/src/test/scala/onparity/domain/service/IngestResumableSuite.scala` (new file)

**Interfaces:**
- Consumes: `FileInventoryStatus.PROCESSING`, `FileInventory.lastProcessedLine` (Task 1).
- Produces:
  - `case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)`
  - `enum BatchDecision { case Commit, Rollback }`
  - `IngestionResult` gains `lastProcessedLine: Option[Long] = None` and `completed: Boolean = true` (both keep `ingest`'s existing construction site — `recordCount = lineCounter, ingestionTimeSeconds = ...` — valid via defaults).
  - `Ingestor.ingestResumable(movementSpec, fileSourceEntry, is, startingLine: Long = 0, batchSize: Int = 100, fileInventoryID: Option[ID] = None, onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit): IngestionResult` — used by Task 3's `ingestFileFromInventory` and directly by tests.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/scala/onparity/domain/service/IngestResumableSuite.scala`:

```scala
package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig}
import onparity.domain.DataSetup
import onparity.domain.model.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class IngestResumableSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given ledger: Ledger = Ledger()

  private def csvSpec(spec: MovementSpec): MovementSpec =
    spec.copy(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id"),
            amountField       = Some(1)
          )
        )
      )
    )

  // id,amount rows 1..5 -> amounts 10,20,30,40,50
  private def fiveRowContent: String =
    (1 to 5).map(i => s"$i,${i * 10}").mkString("\n")

  private def teardown(data: DataSetup): Unit =
    transact(transactor):
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  test("earlier batches commit durably even when a later batch is rolled back"):
    val data = transact(transactor)(DataSetup())
    try
      val spec      = csvSpec(data.journalToRecon)
      val ingestor  = Ingestor()
      val is        = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8))

      val result = ingestor.ingestResumable(
        movementSpec    = spec,
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = is,
        batchSize       = 2,
        onBatchComplete = progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.recordCount, 4)
      assertEquals(result.lastProcessedLine, Some(3L))

      val committedMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(committedMovements.size, 4, "only the two committed batches (lines 0-3) should be visible")
      assertEquals(committedMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40))
    finally teardown(data)

  test("resuming from lastProcessedLine processes remaining rows exactly once"):
    val data = transact(transactor)(DataSetup())
    try
      val spec     = csvSpec(data.journalToRecon)
      val ingestor = Ingestor()

      val first = ingestor.ingestResumable(
        movementSpec    = spec,
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8)),
        batchSize       = 2,
        onBatchComplete = progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)

      val second = ingestor.ingestResumable(
        movementSpec    = spec,
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8)),
        startingLine    = first.lastProcessedLine.map(_ + 1).getOrElse(0L),
        batchSize       = 2
      )
      assertEquals(second.completed, true)
      assertEquals(second.recordCount, 1, "only the previously-uncommitted 5th row")

      val allMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(allMovements.size, 5, "no duplicates and nothing missing")
      assertEquals(allMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40 + 50))
    finally teardown(data)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly onparity.domain.service.IngestResumableSuite"`

Expected: FAIL to compile — `Ingestor.ingestResumable` doesn't exist yet, `BatchDecision`/`BatchProgress` don't exist, `IngestionResult.completed`/`.lastProcessedLine` don't exist.

- [ ] **Step 3: Add the `given Transactor` in `Main.scala`**

`Main.scala` constructs `given ingestor: Ingestor = Ingestor()` (`Main.scala:42`) but has no `given Transactor` anywhere in scope at that point — every `fork` block builds its own local `Transactor(dataSource = given_DataSource)` instead of using a `given`. `Ingestor` is about to require a `Transactor` dependency, so add one. In `backend/src/main/scala/onparity/app/Main.scala`, replace:

```scala
    given DataSource = DbDataSource.create(ConfigFactory.load("application.conf"))
    given clock:              ClockService           = ClockServiceLive()
```

with:

```scala
    given DataSource = DbDataSource.create(ConfigFactory.load("application.conf"))
    given transactor:         Transactor             = Transactor(dataSource = given_DataSource)
    given clock:              ClockService           = ClockServiceLive()
```

- [ ] **Step 4: Implement `ingestResumable`**

In `backend/src/main/scala/onparity/domain/service/Ingestor.scala`, first replace the `IngestionResult` case class:

```scala
case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long
)
```

with:

```scala
case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long,
  lastProcessedLine:    Option[Long] = None,
  completed:            Boolean      = true
)

case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)

enum BatchDecision:
  case Commit, Rollback

private case object IngestionStopped extends RuntimeException
```

Then add `Transactor` to `Ingestor`'s constructor. Replace:

```scala
class Ingestor(using
  val ledger:            Ledger,
  val movementRepo:      MovementRepo,
  val movementSpecRepo:  MovementSpecRepo,
  val accountRepo:       AccountRepo,
  val eventStore:        EventStore,
  val fileInventoryRepo: FileInventoryRepo,
  val fileSourceFactory: FileSourceFactory
):
```

with:

```scala
class Ingestor(using
  val ledger:            Ledger,
  val movementRepo:      MovementRepo,
  val movementSpecRepo:  MovementSpecRepo,
  val accountRepo:       AccountRepo,
  val eventStore:        EventStore,
  val fileInventoryRepo: FileInventoryRepo,
  val fileSourceFactory: FileSourceFactory,
  val transactor:        Transactor
):
```

Add the import for `Transactor`/`transact`/`DbTx` at the top of the file. Replace:

```scala
import com.fasterxml.uuid.Generators
import com.typesafe.scalalogging.Logger
import ox.flow.Flow
```

with:

```scala
import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.fasterxml.uuid.Generators
import com.typesafe.scalalogging.Logger
import ox.flow.Flow
```

Do not touch `ingestFileFromInventory` or `ingest` in this step (Task 3 changes `ingestFileFromInventory`; `ingest` never changes). Add `ingestResumable` as a new method, right after `ingest` and before `detectAndQueue`:

```scala
  def ingestResumable(
    movementSpec:    MovementSpec,
    fileSourceEntry: FileSourceEntry,
    is:              InputStream,
    startingLine:    Long                           = 0,
    batchSize:       Int                            = 100,
    fileInventoryID: Option[ID]                      = None,
    onBatchComplete: BatchProgress => BatchDecision  = _ => BatchDecision.Commit
  ): IngestionResult =
    val beginTime = now()
    val parser    = Parser(movementSpec.dataSourceConfig().parserConfig)

    var recordCount       = 0
    var lastCommittedLine = Option.empty[Long]
    var completed         = true

    try
      Flow
        .fromInputStream(is)
        .linesUtf8
        .zipWithIndex
        .drop(startingLine.toInt)
        .map((line, idx) => (idx, parser.parse(line, idx)))
        .collect { case (idx, Some(parsedLine)) =>
          val amount = parsedLine.maybeAmount.getOrElse(BigDecimal(0))
          val idempotencyId = Generators
            .nameBasedGenerator()
            .generate(s"${fileSourceEntry.name} $idx")
          (idx, MovementRequest(amount = amount, referenceId = idempotencyId, properties = parsedLine.props))
        }
        .grouped(batchSize)
        .runForeach: batch =>
          val lastLineInBatch = batch.last._1
          val requests        = batch.map(_._2)

          val decision = transact(transactor):
            ledger.recordMovements(movementSpec = movementSpec, movementRequests = requests)

            fileInventoryID.foreach: id =>
              val fileInventory = fileInventoryRepo.findById(id).ErrIfNotFound("FileInventory", id)
              fileInventoryRepo.update(
                fileInventory.copy(
                  lastProcessedLine = Some(lastLineInBatch),
                  status            = FileInventoryStatus.PROCESSING
                )
              )

            val progress      = BatchProgress(linesProcessedInBatch = batch.size, lastLineInBatch = lastLineInBatch)
            val batchDecision = onBatchComplete(progress)
            if batchDecision == BatchDecision.Rollback then summon[DbTx].connection.rollback()
            batchDecision

          decision match
            case BatchDecision.Commit =>
              recordCount       += batch.size
              lastCommittedLine  = Some(lastLineInBatch)
            case BatchDecision.Rollback =>
              completed = false
              throw IngestionStopped
    catch case IngestionStopped => ()

    if lastCommittedLine.isDefined then
      transact(transactor):
        eventStore.publishEvent(MoneyMovementsIngested(movementSpecID = movementSpec.id))

    IngestionResult(
      recordCount          = recordCount,
      ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond,
      lastProcessedLine    = lastCommittedLine,
      completed            = completed
    )
```

`onBatchComplete`'s default (`_ => BatchDecision.Commit`) is what every production call site gets; the manual `.rollback()` mirrors the existing `rollBackOnly` test helper's own mechanism (`dbTx.connection.rollback()`), and `transact`'s own post-block handling of an already-rolled-back connection is the same pattern `rollBackOnly` already relies on for every `rollBackOnly`-wrapped test in this codebase — this is not new, riskier behavior.

- [ ] **Step 5: Run tests to verify they pass**

Run: `sbt "backend/testOnly onparity.domain.service.IngestResumableSuite"`

Expected: PASS.

- [ ] **Step 6: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS (same tests as before plus the two new ones; `ingest` and everything that calls it is untouched, and `Main.scala` compiles with the new `given transactor`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/scala/onparity/domain/service/Ingestor.scala \
        backend/src/main/scala/onparity/app/Main.scala \
        backend/src/test/scala/onparity/domain/service/IngestResumableSuite.scala
git commit -m "Add Ingestor.ingestResumable: batched commits with a resumable checkpoint

Ingest stays untouched. ingestResumable commits one real transaction per
batch, persisting a raw-line-index checkpoint on FileInventory in the
same transaction as the batch's data changes, so a crash mid-batch can
never leave the checkpoint ahead of (or behind) the data it describes.
An onBatchComplete callback lets tests simulate a crash at a chosen
batch to verify resume behavior; production always uses the default
(always commit)."
```

---

### Task 3: `ingestFileFromInventory` uses `ingestResumable` and resumes real files

**Files:**
- Modify: `backend/src/main/scala/onparity/domain/service/Ingestor.scala` (only `ingestFileFromInventory`)
- Modify: `backend/src/main/scala/onparity/app/Main.scala:102-116` (the ingestion job-processing fork)
- Test: `backend/src/test/scala/onparity/adapter/driven/CreateFileInventoryTrackerS3Suite.scala` (add one test)

**Interfaces:**
- Consumes: `Ingestor.ingestResumable` (Task 2), `FileInventory.lastProcessedLine` (Task 1).
- Produces: `Ingestor.ingestFileFromInventory(fileInventoryID: ID, batchSize: Int = 100, onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit): IngestionResult` — no longer `Txn[IngestionResult]`; callers (`Main.scala`) no longer wrap it in their own `transact(...)`.

- [ ] **Step 1: Write the failing test**

`CreateFileInventoryTrackerS3Suite.scala` already has the localstack S3 scaffolding this test needs (`s3Client`, `bucket`, `put(key, content)`, and `given`s for `movementRepo`/`accountRepo`/`movementSpecRepo`/`fileInventoryRepo`/etc., plus `afterAll` bucket cleanup). Add this test at the end of the `class CreateFileInventoryTrackerS3Suite` body, after the existing `test("detectAndQueue picks up newly added S3 files on subsequent runs")`:

```scala
  private def csvSpec(spec: MovementSpec): MovementSpec =
    spec.copy(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id"),
            amountField       = Some(1)
          ),
          // ingestFileFromInventory (unlike ingest/ingestResumable called directly) actually calls
          // fileSourceFactory.createFileSource(connectionConfig) to fetch the file, and
          // DataSourceConfig.connectionConfig defaults to S3ConnectionConfig("", "") (empty bucket) —
          // must point at the real test bucket or the fetch fails against a nonexistent bucket.
          connectionConfig = S3ConnectionConfig(bucket, "resume-test/")
        )
      )
    )

  test("ingestFileFromInventory resumes from the persisted checkpoint and marks COMPLETED only once finished"):
    put("resume-test/journal.csv", "1,10\n2,20\n3,30\n4,40\n5,50")

    val data = transact(transactor)(DataSetup())

    transact(transactor):
      movementSpecRepo.update(csvSpec(data.journalToRecon))

    val s3Entry = S3FileSourceEntry(
      bucket         = bucket,
      key            = "resume-test/journal.csv",
      fileSizeBytes  = 0L,
      lastModifiedAt = clockService.now
    )
    val fileInventory = FileInventory(
      movementSpecId      = data.journalToRecon.id,
      sourceSystem        = "S3",
      fileSourceEntryJson = upickle.write(s3Entry),
      fileSizeBytes       = 0L,
      lastModifiedAt      = clockService.now,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = None,
      updatedAt           = None
    )
    transact(transactor)(fileInventoryRepo.insert(fileInventory))

    try
      val ingestor = Ingestor()

      val first = ingestor.ingestFileFromInventory(
        fileInventory.id,
        batchSize       = 2,
        onBatchComplete = progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)
      assertEquals(first.recordCount, 4)

      val afterFirst = transact(transactor)(fileInventoryRepo.findById(fileInventory.id).get)
      assertEquals(afterFirst.status, FileInventoryStatus.PROCESSING)
      assertEquals(afterFirst.lastProcessedLine, Some(3L))

      val second = ingestor.ingestFileFromInventory(fileInventory.id)
      assertEquals(second.completed, true)
      assertEquals(second.recordCount, 1, "only the previously-uncommitted 5th row")

      val afterSecond = transact(transactor)(fileInventoryRepo.findById(fileInventory.id).get)
      assertEquals(afterSecond.status, FileInventoryStatus.COMPLETED)

      val allMovements = transact(transactor)(movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(allMovements.size, 5)
      assertEquals(allMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40 + 50))
    finally
      transact(transactor):
        fileInventoryRepo.deleteById(fileInventory.id)
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)
```

Add the needed imports to `CreateFileInventoryTrackerS3Suite.scala`. Replace:

```scala
import onparity.common.model.S3ConnectionConfig
import onparity.domain.model.{FileInventoryStatus, S3FileSourceEntry}
```

with:

```scala
import onparity.common.model.{CSVParserConfig, DataSourceConfig, S3ConnectionConfig}
import onparity.domain.DataSetup
import onparity.domain.model.{FileInventory, FileInventoryStatus, MovementSpec, S3FileSourceEntry}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly onparity.adapter.driven.CreateFileInventoryTrackerS3Suite"`

Expected: FAIL — `ingestFileFromInventory` still calls the old `ingest` inside one ambient transaction and doesn't know about `batchSize`/`onBatchComplete`/checkpoints yet, so this won't even compile until Step 3 is done.

- [ ] **Step 3: Rewrite `ingestFileFromInventory`**

In `backend/src/main/scala/onparity/domain/service/Ingestor.scala`, replace:

```scala
  def ingestFileFromInventory(fileInventoryID: ID): Txn[IngestionResult] =
    val fileInventory = fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)

    // fixme there is probably a better way to make the relationship between file source
    val entry = fileInventory.fileSourceEntry()

    val movementSpec = movementSpecRepo
      .findById(fileInventory.movementSpecId)
      .ErrIfNotFound("MovementSpec", fileInventory.movementSpecId)

    val connectionConfig = movementSpec.dataSourceConfig().connectionConfig
    val fileSource       = fileSourceFactory.createFileSource(connectionConfig)

    val fileSourceEntry = fileInventory.fileSourceEntry()
    val inputStream     = fileSource.getFile(fileSourceEntry)

    ingest(movementSpec, fileSourceEntry, inputStream)
```

with:

```scala
  def ingestFileFromInventory(
    fileInventoryID: ID,
    batchSize:       Int                           = 100,
    onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): IngestionResult =
    val (fileInventory, movementSpec) = transact(transactor):
      val fileInventory = fileInventoryRepo
        .findById(fileInventoryID)
        .ErrIfNotFound("FileInventory", fileInventoryID)
      val movementSpec = movementSpecRepo
        .findById(fileInventory.movementSpecId)
        .ErrIfNotFound("MovementSpec", fileInventory.movementSpecId)
      (fileInventory, movementSpec)

    val connectionConfig = movementSpec.dataSourceConfig().connectionConfig
    val fileSource        = fileSourceFactory.createFileSource(connectionConfig)
    val fileSourceEntry    = fileInventory.fileSourceEntry()
    val inputStream          = fileSource.getFile(fileSourceEntry)
    val startingLine       = fileInventory.lastProcessedLine.map(_ + 1).getOrElse(0L)

    val result = ingestResumable(
      movementSpec     = movementSpec,
      fileSourceEntry  = fileSourceEntry,
      is               = inputStream,
      startingLine     = startingLine,
      batchSize        = batchSize,
      fileInventoryID  = Some(fileInventory.id),
      onBatchComplete  = onBatchComplete
    )

    if result.completed then
      transact(transactor):
        fileInventoryRepo.update(
          fileInventoryRepo
            .findById(fileInventory.id)
            .ErrIfNotFound("FileInventory", fileInventory.id)
            .copy(status = FileInventoryStatus.COMPLETED)
        )

    result
```

This is no longer `Txn[IngestionResult]` — it owns its own transactions (the initial lookup, `ingestResumable`'s per-batch commits, and the final completion update) rather than borrowing an ambient one.

- [ ] **Step 4: Update `Main.scala`'s ingestion job loop**

`ingestFileFromInventory` isn't `Txn[T]` anymore, so it can't be called from inside the existing outer `transact(...)` the way it was. In `backend/src/main/scala/onparity/app/Main.scala`, replace:

```scala
      fork:
        jobManager.pollForJob(JobType.Ingestion, 5): job =>
          transact(Transactor(dataSource = given_DataSource)):
            try {
              val results = ingestor.ingestFileFromInventory(job.referenceId)
              jobManager.completeJob(
                job.id,
                IngestionInfo( // fixme. should remove these info objects and use events instead
                  numberOfRecordsIngested = results.recordCount,
                  processingTimeSeconds   = results.ingestionTimeSeconds
                )
              )
            } catch
              case ex: Exception =>
                throw new Exception(s"process ingestion jobs. job.referenceId = ${job.referenceId}", ex)
```

with:

```scala
      fork:
        jobManager.pollForJob(JobType.Ingestion, 5): job =>
          try {
            val results = ingestor.ingestFileFromInventory(job.referenceId)
            transact(Transactor(dataSource = given_DataSource)):
              jobManager.completeJob(
                job.id,
                IngestionInfo( // fixme. should remove these info objects and use events instead
                  numberOfRecordsIngested = results.recordCount,
                  processingTimeSeconds   = results.ingestionTimeSeconds
                )
              )
          } catch
            case ex: Exception =>
              throw new Exception(s"process ingestion jobs. job.referenceId = ${job.referenceId}", ex)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "backend/testOnly onparity.adapter.driven.CreateFileInventoryTrackerS3Suite"`

Expected: PASS (requires localstack running on `http://localhost:4566`, same as the suite's existing tests).

- [ ] **Step 6: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/scala/onparity/domain/service/Ingestor.scala \
        backend/src/main/scala/onparity/app/Main.scala \
        backend/src/test/scala/onparity/adapter/driven/CreateFileInventoryTrackerS3Suite.scala
git commit -m "Wire ingestFileFromInventory through ingestResumable

ingestFileFromInventory now computes its starting line from
FileInventory.lastProcessedLine, delegates to ingestResumable for the
batched/checkpointed processing, and marks the file COMPLETED only once
the stream is fully exhausted (not stopped early by a simulated crash).
Main.scala's ingestion job loop no longer wraps the whole call in one
outer transaction, since ingestFileFromInventory now owns its own."
```

---

### Task 4: Point `LargeFileIngestionReconciliationSuite` at `ingestResumable`

**Files:**
- Modify: `backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala`

**Interfaces:**
- Consumes: `Ingestor.ingestResumable` (Task 2).

- [ ] **Step 1: Update the test**

This suite is excluded from the normal `sbt test` cycle already (`build.sbt`'s `Tests.Filter`), so there's no RED/GREEN cycle to run interactively here — a full run takes on the order of hours. Update the code directly, and verify it with a smoke run at a reduced row count (Step 2) rather than the real 5,000,000-row run.

In `backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala`, replace the whole `test(...)` body — currently:

```scala
  test("ingesting and reconciling two 5,000,000-row files nets the recon account to zero"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val journalSpec = csvSpec(data.journalToRecon)
        val reportSpec  = csvSpec(data.reportToRecon)

        val journalStream = BufferedInputStream(FileInputStream(LargeFileFixtures.journalFile()), 1 << 20)
        try ingestor.ingest(journalSpec, InMemoryFileSourceEntry("journal-large"), journalStream)
        finally journalStream.close()

        val reportStream = BufferedInputStream(FileInputStream(LargeFileFixtures.reportFile()), 1 << 20)
        try ingestor.ingest(reportSpec, InMemoryFileSourceEntry("report-large"), reportStream)
        finally reportStream.close()

        val reconciler = Reconciler(
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )
        reconciler.reconcileAccount(data.reconAccount)

        val reconAccountAfter = accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccountAfter.balance, BigDecimal(0), "reconAccount.balance")
        assertEquals(reconAccountAfter.unreconciledBalance, BigDecimal(0), "reconAccount.unreconciledBalance")
        assertEquals(
          movementRepo.countUnreconciledByAccount(data.reconAccount.id),
          0,
          "movements left unreconciled on the recon account"
        )
```

with:

```scala
  test("ingesting and reconciling two 5,000,000-row files nets the recon account to zero"):
    val ingestor = Ingestor()
    val data     = transact(transactor)(DataSetup())
    try
      val journalSpec = csvSpec(data.journalToRecon)
      val reportSpec  = csvSpec(data.reportToRecon)

      val journalStream = BufferedInputStream(FileInputStream(LargeFileFixtures.journalFile()), 1 << 20)
      try ingestor.ingestResumable(journalSpec, InMemoryFileSourceEntry("journal-large"), journalStream)
      finally journalStream.close()

      val reportStream = BufferedInputStream(FileInputStream(LargeFileFixtures.reportFile()), 1 << 20)
      try ingestor.ingestResumable(reportSpec, InMemoryFileSourceEntry("report-large"), reportStream)
      finally reportStream.close()

      val reconciler = Reconciler(
        accountRepo        = data.accountRepo,
        movementRepo       = data.movementRepo,
        movementSpecRepo   = data.movementSpecRepo,
        reconSpecRepo      = data.reconSpecRepo,
        reconRepo          = data.reconRepo,
        fundFlowConfigRepo = data.fundFlowConfigRepo
      )
      transact(transactor)(reconciler.reconcileAccount(data.reconAccount))

      val reconAccountAfter = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(reconAccountAfter.balance, BigDecimal(0), "reconAccount.balance")
      assertEquals(reconAccountAfter.unreconciledBalance, BigDecimal(0), "reconAccount.unreconciledBalance")
      assertEquals(
        transact(transactor)(movementRepo.countUnreconciledByAccount(data.reconAccount.id)),
        0,
        "movements left unreconciled on the recon account"
      )
    finally
      transact(transactor):
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)
```

`reconciler.reconcileAccount(...)` is itself `Txn[Unit]`, so it now needs its own `transact(transactor) { ... }` wrapper too, since there's no longer an enclosing `rollBackOnly` transaction providing an ambient one.

`ingestResumable`'s batches commit for real on their own connection, so `DataSetup()` must be committed on its own (`transact(transactor)(DataSetup())`, no `rollBackOnly`) before `ingestResumable` is called — otherwise `ingestResumable`'s first batch wouldn't be able to see `data`'s accounts yet, and `Ledger.recordMovements` would throw `NotFoundError`. The `finally` block replaces the old reliance on `rollBackOnly` to leave the database clean: deleting `fundFlowConfig` cascades (per `backend/db/schema.sql`'s `ON DELETE CASCADE` chain: `fund_flow_config` → `account` → `movement_spec`/`movement`) to clean up everything except `reconciliation_spec`, which has no cascading FK and must be deleted explicitly first.

- [ ] **Step 2: Smoke-verify at a small scale (do not commit this override)**

Temporarily change both `LargeFileFixtures.journalFile()` / `.reportFile()` calls to `LargeFileFixtures.journalFile(rows = 1000)` / `.reportFile(rows = 1000)`, then run:

`sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"`

Expected: PASS in well under a minute. Once it passes, revert both calls back to their no-argument form (the 5,000,000-row default) before committing — do not run the full 5,000,000-row version in this session; it's designed to be run explicitly, separately (`sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"`), and is expected to take on the order of hours given `Reconciler`'s existing per-row matching loop.

- [ ] **Step 3: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS, and `LargeFileIngestionReconciliationSuite` does not appear in the output (still excluded from the normal cycle by `build.sbt`).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala
git commit -m "Point LargeFileIngestionReconciliationSuite at ingestResumable

Switches from ingest to ingestResumable (the method actually meant for
this scale), commits DataSetup() separately instead of relying on an
enclosing rollBackOnly (ingestResumable's batches commit for real on
their own connection regardless), and adds explicit teardown."
```
