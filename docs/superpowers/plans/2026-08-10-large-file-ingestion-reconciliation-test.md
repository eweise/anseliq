# Large-File Ingestion & Reconciliation Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicitly-invoked backend test that ingests two 5,000,000-row CSV files and reconciles them, verifying the recon account nets to zero balance and every movement gets reconciled — while fixing the pre-existing bug that currently makes reconciliation matching always return zero results.

**Architecture:** Three layers, each independently testable: (1) a targeted bug fix to reconciliation-matching SQL so `Reconciler.reconcileAccount` actually matches movements, proven with a small fast test; (2) a reusable `LargeFileFixtures` generator that produces (and caches, outside the sbt resource pipeline) large `id,amount` CSV files on demand, proven with small row counts; (3) a new, explicitly-invoked test suite that wires the two together at 5,000,000 rows and is excluded from the normal `sbt test` cycle the same way `DataSetupScenario1` already is.

**Tech Stack:** Scala 3, munit, Magnum/PostgreSQL, sbt multi-project build (`common` + `backend`).

## Global Constraints

- Backend follows hexagonal architecture (see `backend/CLAUDE.md`); repo methods live behind `domain/port/driven` traits with `adapter/driven` implementations.
- Tests use munit with `rollBackOnly` for DB isolation (see `backend/CLAUDE.md`).
- ZIO is not used in new code (existing code only).
- The large-file test must be excluded from the normal `sbt test` cycle but runnable via `sbt "backend/testOnly ..."`, mirroring the existing `DataSetupScenario1` exclusion in `build.sbt`.
- Generated large fixture files must never be written under `src/test/resources` (sbt copies that tree into `target/.../test-classes` on every resource-processing step, regardless of whether the excluded test runs) and must never be committed to git.

---

### Task 1: Fix reconciliation matching bug

**Files:**
- Modify: `common/src/main/scala/onparity/common/model/Rules.scala`
- Modify: `backend/src/main/scala/onparity/domain/port/driven/MovementRepo.scala`
- Modify: `backend/src/main/scala/onparity/adapter/driven/MovementRepoLive.scala`
- Modify: `backend/src/main/scala/onparity/domain/service/Reconciler.scala`
- Modify: `backend/src/test/scala/onparity/adapter/driven/MovementRepoLiveSuite.scala` (compile-only fix to an `.ignore`d test)
- Test: `backend/src/test/scala/onparity/domain/service/ReconcilerSuite.scala`

**Interfaces:**
- Produces: `MovementRepo.findEqualityMatches(sqlCriteria: String, inMovementSpecId: ID, outMovementSpecId: ID, relationship: Relationship): Txn[Vector[(MovementInID, MovementOutID)]]` — the signature every later task's reconciliation call must use (`ID` = `onparity.common.model.model.ID`, i.e. `UUID`).

- [ ] **Step 1: Write the failing test**

In `backend/src/test/scala/onparity/domain/service/ReconcilerSuite.scala`, add a new test (leave the existing `.ignore`d test and `createMovement` helper untouched — this reuses `createMovement`). Insert it after the `test("Post Amount balances".ignore): ...` block and before `def createMovement(...)`:

```scala
  test("reconcileAccount matches movements sharing the same id and marks both sides reconciled"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val reconciler = Reconciler(
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        val idToMatch       = "match-me"
        val journalMovement = createMovement(data, data.journalToRecon, idToMatch)
        val reportMovement  = createMovement(data, data.reportToRecon, idToMatch)

        reconciler.reconcileAccount(data.reconAccount)

        val journalAfter = movementRepo.findById(journalMovement.id).get
        val reportAfter  = movementRepo.findById(reportMovement.id).get
        assert(journalAfter.reconciliationId.nonEmpty, "journal movement should be reconciled")
        assert(reportAfter.reconciliationId.nonEmpty, "report movement should be reconciled")
        assertEquals(journalAfter.reconciliationId, reportAfter.reconciliationId)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly onparity.domain.service.ReconcilerSuite"`

Expected: FAIL — the new test fails its `nonEmpty` assertions because `StringAttributeMatchRule.sql` currently hardcodes `move_in.movement_spec_id = ''` / `move_out.movement_spec_id = ''`, which can never be true, so `findEqualityMatches` returns zero rows and nothing gets reconciled. (The other tests in the file, including the pre-existing `.ignore`d one, are unaffected.)

- [ ] **Step 3: Implement the fix**

In `common/src/main/scala/onparity/common/model/Rules.scala`, both `StringAttributeMatchRule.sql` and `DateAttributeMatchRule.sql` currently end with:

```scala
    s""" move_in.movement_spec_id = ''
       | and move_out.movement_spec_id = ''
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin
```

Replace **both** occurrences (they are byte-identical) with:

```scala
    s"$inModifiedFrag = $outModifiedFrag"
```

In `backend/src/main/scala/onparity/domain/port/driven/MovementRepo.scala`, replace:

```scala
  def findEqualityMatches(
    sqlCriteria:  String,
    relationship: Relationship
  ): Txn[Vector[(MovementInID, MovementOutID)]]
```

with:

```scala
  def findEqualityMatches(
    sqlCriteria:       String,
    inMovementSpecId:  ID,
    outMovementSpecId: ID,
    relationship:      Relationship
  ): Txn[Vector[(MovementInID, MovementOutID)]]
```

In `backend/src/main/scala/onparity/adapter/driven/MovementRepoLive.scala`, replace the whole `findEqualityMatches` method:

```scala
  def findEqualityMatches(
    sqlCriteria:  String,
    relationship: Relationship = OneToOne
  ): Txn[Vector[(MovementInID, MovementOutID)]] =
    val criteria = SqlLiteral(sqlCriteria)
    val orderingClause = SqlLiteral(
      relationship match
        case OneToOne | OneToMany | ManyToMany => " order by move_in.id, move_out.id"
        case ManyToOne                         => " order by move_out.id, move_in.id"
    )

    sql"""
      SELECT move_in.id, move_out.id
      FROM $m AS move_in
      JOIN $m AS move_out
      ON move_in.in_account_id = move_out.out_account_id
      where 1 = 1
      AND $criteria
      $orderingClause
    """.query[(String, String)].run()
```

with:

```scala
  def findEqualityMatches(
    sqlCriteria:       String,
    inMovementSpecId:  ID,
    outMovementSpecId: ID,
    relationship:      Relationship = OneToOne
  ): Txn[Vector[(MovementInID, MovementOutID)]] =
    val criteria = SqlLiteral(sqlCriteria)
    val orderingClause = SqlLiteral(
      relationship match
        case OneToOne | OneToMany | ManyToMany => " order by move_in.id, move_out.id"
        case ManyToOne                         => " order by move_out.id, move_in.id"
    )

    sql"""
      SELECT move_in.id, move_out.id
      FROM $m AS move_in
      JOIN $m AS move_out
      ON move_in.in_account_id = move_out.out_account_id
      where 1 = 1
      AND move_in.movement_spec_id = $inMovementSpecId
      AND move_out.movement_spec_id = $outMovementSpecId
      AND $criteria
      $orderingClause
    """.query[(String, String)].run()
```

In `backend/src/main/scala/onparity/domain/service/Reconciler.scala`, replace:

```scala
      val matches = movementRepo.findEqualityMatches(reconSpec.rule().sql, reconSpec.relationship)
```

with:

```scala
      val matches = movementRepo.findEqualityMatches(
        reconSpec.rule().sql,
        reconSpec.inMovementSpecId,
        reconSpec.outMovementSpecId,
        reconSpec.relationship
      )
```

Finally, fix the two call sites in the `.ignore`d test `test("findMatches".ignore)` in
`backend/src/test/scala/onparity/adapter/driven/MovementRepoLiveSuite.scala` so the file still
compiles (an `.ignore`d munit test is still compiled, just not run). In that test, `inReconMovement`
(`inAccountId = data.reconAccount.id`) plays the `move_in` role with `movementSpecId = data.reportToRecon.id`,
and `outReconMovement` (`outAccountId = data.reconAccount.id`) plays the `move_out` role with
`movementSpecId = data.journalToRecon.id`. Replace:

```scala
        val sqlStr                        = rule.sql
        val results                       = movementRepo.findEqualityMatches(sqlStr)
```

with:

```scala
        val sqlStr                        = rule.sql
        val results                       = movementRepo.findEqualityMatches(sqlStr, data.reportToRecon.id, data.journalToRecon.id)
```

and replace:

```scala
        val sqlStrWithAnd                               = AndRule(List(rule, rule)).sql
        val resultsWithAnd                              = movementRepo.findEqualityMatches(sqlStrWithAnd)
```

with:

```scala
        val sqlStrWithAnd                               = AndRule(List(rule, rule)).sql
        val resultsWithAnd                              = movementRepo.findEqualityMatches(sqlStrWithAnd, data.reportToRecon.id, data.journalToRecon.id)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "backend/testOnly onparity.domain.service.ReconcilerSuite"`

Expected: PASS (all tests in the file, including the new one; the `.ignore`d one still shows as ignored, not run).

- [ ] **Step 5: Run the full backend suite to check for regressions**

Run: `sbt backend/test`

Expected: PASS. (This also confirms `MovementRepoLiveSuite.scala` compiles.)

- [ ] **Step 6: Commit**

```bash
git add common/src/main/scala/onparity/common/model/Rules.scala \
        backend/src/main/scala/onparity/domain/port/driven/MovementRepo.scala \
        backend/src/main/scala/onparity/adapter/driven/MovementRepoLive.scala \
        backend/src/main/scala/onparity/domain/service/Reconciler.scala \
        backend/src/test/scala/onparity/adapter/driven/MovementRepoLiveSuite.scala \
        backend/src/test/scala/onparity/domain/service/ReconcilerSuite.scala
git commit -m "Fix reconciliation matching to filter by real movement spec IDs

StringAttributeMatchRule/DateAttributeMatchRule hardcoded
move_in.movement_spec_id = '' and move_out.movement_spec_id = '', which
can never be true since movement_spec_id is a UUID column — matching
always returned zero rows. findEqualityMatches now takes the real
inMovementSpecId/outMovementSpecId and filters on those instead."
```

---

### Task 2: Large-file fixture generator

**Files:**
- Create: `backend/src/test/scala/onparity/testing/LargeFileFixtures.scala`
- Test: `backend/src/test/scala/onparity/testing/LargeFileFixturesSuite.scala`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `LargeFileFixtures.journalFile(rows: Int = 5_000_000): java.io.File` and `LargeFileFixtures.reportFile(rows: Int = 5_000_000): java.io.File` — used by Task 3. Each returns a `File` containing `rows` lines of `"$id,$amount"` (no header), where `id` is `1..rows` and `amount` is `100 + (id % 900)` formatted as `N.00`. Calling either method twice with the same `rows` value returns the same cached file without regenerating it. Files are cached under `backend/target/large-fixtures/` (outside `src/`, already gitignored via the `target` pattern).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/scala/onparity/testing/LargeFileFixturesSuite.scala`:

```scala
package onparity.testing

import java.nio.file.Files

class LargeFileFixturesSuite extends munit.FunSuite:

  test("journalFile generates the requested number of id,amount rows"):
    val file  = LargeFileFixtures.journalFile(rows = 5)
    val lines = Files.readAllLines(file.toPath)
    assertEquals(lines.size(), 5)
    assertEquals(lines.get(0), "1,101.00")
    assertEquals(lines.get(4), "5,105.00")

  test("reportFile produces the same id,amount rows as journalFile for the same row count"):
    val journalLines = Files.readAllLines(LargeFileFixtures.journalFile(rows = 10).toPath)
    val reportLines  = Files.readAllLines(LargeFileFixtures.reportFile(rows  = 10).toPath)
    assertEquals(journalLines, reportLines)

  test("a cached file is not regenerated on a second call"):
    val first          = LargeFileFixtures.journalFile(rows = 3)
    val firstModified  = first.lastModified()
    val second         = LargeFileFixtures.journalFile(rows = 3)
    assertEquals(second.lastModified(), firstModified)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly onparity.testing.LargeFileFixturesSuite"`

Expected: FAIL to compile — `onparity.testing.LargeFileFixtures` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/test/scala/onparity/testing/LargeFileFixtures.scala`:

```scala
package onparity.testing

import java.io.{BufferedWriter, File, FileWriter}

object LargeFileFixtures:
  private val cacheDir = File("target/large-fixtures")

  def journalFile(rows: Int = 5_000_000): File = cachedFile(s"journal-$rows.csv", rows)

  def reportFile(rows: Int = 5_000_000): File = cachedFile(s"report-$rows.csv", rows)

  private def cachedFile(fileName: String, rows: Int): File =
    val file = File(cacheDir, fileName)
    if !file.exists() then
      cacheDir.mkdirs()
      val writer = BufferedWriter(FileWriter(file), 1 << 20)
      try
        var i = 1
        while i <= rows do
          val amount = 100 + (i % 900)
          writer.write(s"$i,$amount.00")
          writer.newLine()
          i += 1
      finally writer.close()
    file
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "backend/testOnly onparity.testing.LargeFileFixturesSuite"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/scala/onparity/testing/LargeFileFixtures.scala \
        backend/src/test/scala/onparity/testing/LargeFileFixturesSuite.scala
git commit -m "Add LargeFileFixtures generator for large-file ingestion tests

Streams id,amount CSV rows to a cached file under backend/target so
tests can reuse a large fixture across runs without bloating the sbt
resource pipeline or git history."
```

---

### Task 3: Large-file ingestion & reconciliation test, excluded from the normal cycle

**Files:**
- Create: `backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala`
- Modify: `build.sbt`

**Interfaces:**
- Consumes: `MovementRepo.findEqualityMatches` fixed signature (Task 1), `LargeFileFixtures.journalFile`/`reportFile` (Task 2).
- Produces: nothing consumed by later tasks — this is the final deliverable.

- [ ] **Step 1: Write the test**

Create `backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala`:

```scala
package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.*
import onparity.testing.{LargeFileFixtures, rollBackOnly}

import java.io.{BufferedInputStream, FileInputStream}
import javax.sql.DataSource

// Excluded from the normal `sbt test` cycle in build.sbt — ingests and reconciles
// two 5,000,000-row files, which takes on the order of hours given Reconciler's
// current per-row (non-batched) matching loop. Run explicitly with:
//   sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"
class LargeFileIngestionReconciliationSuite extends munit.FunSuite:
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

- [ ] **Step 2: Smoke-verify the logic at a small scale (do not commit this override)**

Running the test as written takes on the order of hours (see the comment above
`class LargeFileIngestionReconciliationSuite`), which is impractical to wait
out during implementation. Instead, temporarily change both
`LargeFileFixtures.journalFile()` and `LargeFileFixtures.reportFile()` calls in
the test to `LargeFileFixtures.journalFile(rows = 1000)` and
`LargeFileFixtures.reportFile(rows = 1000)`, then run:

`sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"`

Expected: PASS in well under a minute. This exercises exactly the same code
path (ingest both files, reconcile, assert zero balances and zero
unreconciled count) as the real 5,000,000-row run, just faster.

Once it passes, revert the two calls back to their no-argument form
(`LargeFileFixtures.journalFile()` / `LargeFileFixtures.reportFile()`, i.e.
the 5,000,000-row default) before committing. Do not run the full
5,000,000-row version as part of this implementation session — it's
designed to be run explicitly, separately, whenever someone wants the true
large-scale check (see Step 4 below for that command).

- [ ] **Step 3: Exclude the suite from the normal `sbt test` cycle**

In `build.sbt`, replace:

```scala
    // DataSetupScenario1 seeds real, committed data into the local dev DB (no rollBackOnly) and is meant
    // to be run explicitly (sbt "testOnly onparity.testing.DataSetupScenario1"), not as part of the
    // regular suite where it would pollute state for every other test. Scoped to the `test` task only
    // (not `testOnly`/`testQuick`) so explicit invocation still works.
    Test / test / testOptions += Tests.Filter(name => name != "onparity.testing.DataSetupScenario1"),
```

with:

```scala
    // DataSetupScenario1 seeds real, committed data into the local dev DB (no rollBackOnly) and is meant
    // to be run explicitly (sbt "testOnly onparity.testing.DataSetupScenario1"), not as part of the
    // regular suite where it would pollute state for every other test. LargeFileIngestionReconciliationSuite
    // ingests and reconciles two 5,000,000-row files, which takes on the order of hours given Reconciler's
    // current per-row matching loop, and is likewise meant to be run explicitly
    // (sbt "testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"). Both are scoped to
    // the `test` task only (not `testOnly`/`testQuick`) so explicit invocation still works.
    Test / test / testOptions += Tests.Filter(name =>
      name != "onparity.testing.DataSetupScenario1" &&
        name != "onparity.domain.service.LargeFileIngestionReconciliationSuite"
    ),
```

- [ ] **Step 4: Verify the exclusion works**

Run: `sbt backend/test`

Expected: PASS, and the output does not show
`LargeFileIngestionReconciliationSuite` running (same as `DataSetupScenario1`
today).

Then confirm explicit invocation still works (this is the real, full
5,000,000-row run — expect it to take a long time, on the order of hours;
this step is documenting the command, not something to wait out in this
session):

`sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"`

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala \
        build.sbt
git commit -m "Add explicitly-invoked large-file ingestion/reconciliation test

Ingests two 5,000,000-row files and reconciles them, asserting the recon
account nets to zero balance/unreconciledBalance and every movement ends
up reconciled. Excluded from the normal sbt test cycle (mirrors the
existing DataSetupScenario1 exclusion) since a full run takes on the
order of hours; run explicitly with
sbt \"backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite\"."
```
