# Large-file ingestion & reconciliation test — design

Date: 2026-08-10

## Purpose

Add an explicitly-invoked (not part of the normal `sbt test` cycle) backend
test that ingests two large (5,000,000-row) CSV files through the real
`Ingestor` / `Ledger` pipeline, reconciles them through `Reconciler`, and
verifies the recon account's `balance` and `unreconciledBalance` both settle
at `0`, and that every ingested movement ends up reconciled. This exercises
the streaming ingestion path and the reconciliation matching path at a scale
the current test suite doesn't touch.

## Prerequisite bug fix

`Reconciler.reconcileAccount` (`backend/src/main/scala/perfectpair/domain/service/Reconciler.scala:31`)
calls `movementRepo.findEqualityMatches(reconSpec.rule().sql, reconSpec.relationship)`.
`StringAttributeMatchRule.sql` and `DateAttributeMatchRule.sql`
(`common/src/main/scala/perfectpair/common/model/Rules.scala:54-77`) each
hardcode:

```scala
s""" move_in.movement_spec_id = ''
   | and move_out.movement_spec_id = ''
   | and $inModifiedFrag = $outModifiedFrag""".stripMargin
```

`movement_spec_id` is a UUID column, so `= ''` can never be true — matching
returns zero rows, always. This is why `ReconcilerSuite`'s one end-to-end
test that calls `reconcileAccount` and asserts on `reconciliationId` is
marked `.ignore` ("broken because of relies on old sql implementation",
`backend/src/test/scala/perfectpair/domain/service/ReconcilerSuite.scala:20`).

Fix, minimal and targeted:

1. Remove the two hardcoded `movement_spec_id = ''` lines from both rules'
   `.sql` in `Rules.scala`, leaving only the attribute-equality predicate
   (`$inModifiedFrag = $outModifiedFrag`).
2. `MovementRepo.findEqualityMatches`
   (`backend/src/main/scala/perfectpair/domain/port/driven/MovementRepo.scala:16`)
   and its impl in `MovementRepoLive.scala:81` gain two new parameters,
   `inMovementSpecId: ID` and `outMovementSpecId: ID`, bound via magnum's
   safe `$` interpolation and added as `AND move_in.movement_spec_id = ...
   AND move_out.movement_spec_id = ...` filters in the query.
3. `Reconciler.reconcileAccount` passes `reconSpec.inMovementSpecId` and
   `reconSpec.outMovementSpecId` into the call.

No other behavior changes. The existing `.ignore`d test in `ReconcilerSuite`
is left as-is (out of scope for this change).

## Fixture generator

New object `perfectpair.testing.LargeFileFixtures` in
`backend/src/test/scala/perfectpair/testing/LargeFileFixtures.scala`:

- `journalFile(rows: Int = 5_000_000): File`
- `reportFile(rows: Int = 5_000_000): File`

Each method generates its file only if it doesn't already exist at a fixed
cache path (streamed line-by-line via `BufferedWriter`, never holding all
rows in memory), then returns the `File`. Re-running the test suite reuses
the cached file instantly.

Cache location: `backend/src/test/resources/large-fixtures/journal-5000000.csv`
and `.../report-5000000.csv`. The `large-fixtures/` subdirectory is added to
`.gitignore` so these files (hundreds of MB) are never committed, while
still living alongside other test resources for discoverability.

Row format (no header): `id,amount`, e.g. `"482913,187.00"`.

- `id` = the row index as a string, `1` through `rows` — guarantees
  uniqueness and a clean 1:1 (`OneToOne`) match between the two files.
- `amount` = `100 + (i % 900)`, formatted with 2 decimal places — varies
  per row without breaking the zero-sum property, since both files use the
  identical `(id, amount)` pair for row `i`.

Because `journalToRecon` posts *into* the recon account and `reportToRecon`
posts *out of* the recon account (per `DataSetup`), and every row has an
identical counterpart across the two files, the recon account's `balance`
nets to `0` purely from ingestion — independent of whether reconciliation
matching succeeds. The "all movements reconciled" assertion is what actually
depends on the bug fix above.

## Test

New suite `perfectpair.domain.service.LargeFileIngestionReconciliationSuite`
in `backend/src/test/scala/perfectpair/domain/service/LargeFileIngestionReconciliationSuite.scala`,
following the existing `IngestorSuite` / `ReconcilerSuite` wiring pattern
(`munit.FunSuite`, `Transactor` + `rollBackOnly` for DB isolation, real
Postgres via `test-application.conf`).

Single test, "ingest and reconcile two 5,000,000-row files nets recon
account to zero":

1. Build a `DataSetup` (gives `reconAccount`, `journalAccount`,
   `reportAccount`, `journalToRecon`, `reportToRecon`, and a `reconSpec` on
   `reconAccount` matching on the `id` property).
2. `Ingestor().ingest(data.journalToRecon, InMemoryFileSourceEntry("journal-large"), FileInputStream(LargeFileFixtures.journalFile()))`
3. `Ingestor().ingest(data.reportToRecon, InMemoryFileSourceEntry("report-large"), FileInputStream(LargeFileFixtures.reportFile()))`
4. `Reconciler(...).reconcileAccount(data.reconAccount)`
5. Re-fetch `reconAccount` via `accountRepo.findById` and assert:
   - `balance == 0`
   - `unreconciledBalance == 0`
   - `movementRepo.countUnreconciledByAccount(reconAccount.id) == 0` (every
     movement on the recon account ended up reconciled — uses the existing
     `MovementRepoLive.countUnreconciledByAccount` so this doesn't require
     loading 5M+ movement rows into test-process memory)

No assertions on movement counts or elapsed time — kept to what was asked
for plus the "all reconciled" check.

## Known runtime cost (not addressed by this change)

`Reconciler.reconcileAccount`'s matching loop is not batched: for a
`OneToOne` relationship it inserts one `Reconciliation` row per matched
pair, and updates each of the two matched movements with an individual
`findById` + `update` round trip. At 5,000,000 matched pairs this is
~5,000,000 `Reconciliation` inserts plus ~10,000,000 individual
find/update round trips — realistically a multi-hour run. This is a known,
pre-existing performance characteristic of `Reconciler`, not something this
change fixes; the test is intentionally excluded from the normal cycle so
this cost is only paid on explicit invocation.

## Excluding from the normal test cycle

Mirror the existing `DataSetupScenario1` pattern in `build.sbt:130-134`:
add the new suite's fully-qualified name to the same
`Test / test / testOptions += Tests.Filter(...)` exclusion, with a comment
explaining why. This excludes it from plain `sbt test` while leaving
`sbt "testOnly perfectpair.domain.service.LargeFileIngestionReconciliationSuite"`
(and `testQuick`) working for explicit/manual runs.

## Out of scope

- Fixing `Reconciler.reconcileAccount`'s per-row matching/update loop to be
  batched (would be a larger, separate change).
- Un-ignoring the existing broken test in `ReconcilerSuite`.
- Any CI wiring to run this test on a schedule — purely a manually-invoked
  fixture/test for now.
