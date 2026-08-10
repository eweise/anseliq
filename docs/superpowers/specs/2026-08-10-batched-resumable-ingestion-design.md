# Batched, resumable ingestion — design

Date: 2026-08-10

## Problem

`Ingestor.ingest` (`backend/src/main/scala/perfectpair/domain/service/Ingestor.scala:48`)
is `Txn[IngestionResult]` — a context function requiring an ambient `DbTx`
(`type Txn[T] = DbTx ?=> T`, `backend/src/main/scala/perfectpair/domain/port/driven/package.scala:6`).
The transaction boundary is established entirely by the caller. In
production, that caller is the ingestion job loop in
`backend/src/main/scala/perfectpair/app/Main.scala:104-116`, which wraps
the *entire* `ingestor.ingestFileFromInventory(...)` call — every batch,
for a 5,000,000-row file that's ~50,000 batches of 100 — in one
`transact(...)` block. The transaction stays open for the whole file, and
if the process crashes partway through, nothing is committed and the next
attempt has to reprocess the file from line one.

There's also a latent correctness issue this surfaces: `Ledger.recordMovements`
(`backend/src/main/scala/perfectpair/domain/service/Ledger.scala:40-43`)
applies `balance +=`/`-=` unconditionally, regardless of whether the
paired `INSERT ... ON CONFLICT(reference_id) DO NOTHING` actually inserted
a row or silently skipped a duplicate. If a partially-processed file were
ever reprocessed from the start, already-committed rows would have their
balance effect double-applied even though the duplicate movement row
itself is correctly skipped.

## Decision: mid-file checkpoint + per-batch commits, not file chunking

Considered and rejected: splitting large files into many smaller
`FileInventory` jobs up front, each still processed in one transaction
like today (no new commit/checkpoint machinery in `Ingestor` at all).
Rejected because the only version of that approach which doesn't waste
I/O on every chunk requires byte-range-based splitting — `FileSource`
gaining ranged reads, a chunk-creation step that aligns byte offsets to
newlines, and reworking how idempotency keys are derived (they currently
assume one contiguous line count per file: `perfectpair.domain.service.Ingestor.scala:76-80`).
That's more upfront surface area than the checkpoint approach, which
touches only `Ingestor`, `FileInventory`, and the two call sites, and only
pays the "re-read to catch up" cost on an actual crash rather than on
every chunk boundary in the happy path.

## Design

### Schema change

`recon.file_inventory` (`backend/db/schema.sql:89-102`) gains a nullable
`last_processed_line BIGINT` column, mapping to the new
`FileInventory.lastProcessedLine: Option[Long]` field. No enum-type
migration is needed for the new `PROCESSING` status value — `status` is
already a plain `TEXT NOT NULL DEFAULT 'QUEUED'` column, not a native
Postgres enum, so any string the Scala `FileInventoryStatus` enum produces
is already valid. There's no Flyway (or other) migration runner wired up
in the app (`grep` for `Flyway` under `backend/src/main` finds nothing);
schema changes are made directly in `schema.sql`, applied via `make
create-db` / `make restore-db`.

### Checkpoint = raw line index, unified with the idempotency key

`Ingestor.ingest` currently threads two different counters: the raw
`zipWithIndex` line number (used only to detect the header row) and a
separately incremented `lineCounter` (used to build the idempotency key,
counting only lines that parsed and passed the filter). This design
collapses them into one: **the raw `zipWithIndex` line index is used both
as the checkpoint value and as the idempotency-key input.** Concretely,
the raw index is carried through the filter step instead of being dropped:

```scala
Flow
  .fromInputStream(is)
  .linesUtf8
  .zipWithIndex
  .drop(startingLine)
  .map((line, idx) => (idx, parser.parse(line, idx)))
  .collect { case (idx, Some(parsedLine)) => (idx, parsedLine) }
  .map((idx, parsedLine) =>
    val idempotencyId = Generators.nameBasedGenerator().generate(s"${fileSourceEntry.name} $idx")
    (idx, MovementRequest(amount = ..., referenceId = idempotencyId, properties = parsedLine.props))
  )
  .grouped(batchSize)
  .runForeach(batch => /* commit this batch, see below */)
```

This removes the need to track "raw lines skipped" and "accepted lines
counted" as two separate numbers that would otherwise have to stay in
sync across a resume.

### `Ingestor` owns its own transactions

`Ingestor` gains a `Transactor` dependency (added to its existing `using`
clause alongside `ledger`, `movementRepo`, etc.). `ingest` stops being
`Txn[IngestionResult]` and becomes a plain method — it calls
`transact(transactor) { ... }` itself, once per batch. Each batch's
transaction contains, atomically:

1. `ledger.recordMovements(...)` for that batch (existing behavior,
   unchanged).
2. An update to `FileInventory.lastProcessedLine` to the highest raw line
   index committed in this batch, and `FileInventory.status` to the new
   `FileInventoryStatus.PROCESSING` value (added alongside the existing
   `QUEUED`/`COMPLETED`) if it isn't already past `QUEUED`.

Because the checkpoint update happens in the *same* transaction as the
batch's data changes, a crash mid-batch can never leave the checkpoint
ahead of — or behind — the data it describes: either both happened, or
neither did.

`ingest`'s new signature:

```scala
case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)
enum BatchDecision:
  case Commit, Rollback

def ingest(
  movementSpec:    MovementSpec,
  fileSourceEntry: FileSourceEntry,
  is:              InputStream,
  startingLine:    Long                        = 0,
  batchSize:       Int                         = 100,
  onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
): IngestionResult
```

`IngestionResult` gains a `lastProcessedLine: Option[Long]` field (the
checkpoint after this call — `None` if no batch committed) so a caller
that isn't going through `FileInventory` at all (e.g. a unit test calling
`ingest` directly) still has what it needs to resume manually:
`ingest(..., startingLine = result.lastProcessedLine.map(_ + 1).getOrElse(0))`.

### The commit/rollback callback

`onBatchComplete` exists specifically so tests can simulate a crash at a
known point and verify resume behavior, without adding any
test-only branching to production code: production always passes the
default (`_ => Commit`). A test can let the first few batches commit
normally, then return `Rollback` on a chosen batch. When a batch's
decision is `Rollback`:

- That batch's transaction is rolled back (mirroring today's
  `rollBackOnly` helper — same "roll back this connection" mechanism, just
  scoped to one batch instead of one whole test).
- `ingest` stops immediately, as if the process had crashed at that point.
  It does not attempt further batches.
- The checkpoint remains at the last *committed* batch (the rolled-back
  batch's checkpoint update never took effect).

A resume test then calls `ingest` again (or `ingestFileFromInventory`) and
asserts it picks up from the last committed checkpoint rather than
reprocessing from line 0.

### Resuming a file

`ingestFileFromInventory` (`Ingestor.scala:28`) changes to:

1. Look up `FileInventory` and `MovementSpec` in one short, separate
   `transact(transactor) { ... }` block (a plain read — these lookups are
   `Txn[T]` today and still need an ambient `DbTx` for that one block, but
   `ingestFileFromInventory` as a whole is no longer itself `Txn[T]`, for
   the same reason `ingest` isn't: it now owns its transactions rather
   than borrowing the caller's).
2. Compute `startingLine = fileInventory.lastProcessedLine.map(_ + 1).getOrElse(0L)`
   (`lastProcessedLine` becomes `Option[Long] = None` on a fresh
   `FileInventory`, distinguishing "nothing processed yet" from "processed
   through line 0").
3. Open the file via `fileSource.getFile(fileSourceEntry)` (unchanged —
   still a full re-read from the start of the stream; only the in-memory
   `.drop(startingLine)` skips already-processed lines, since plain
   `InputStream`s aren't seekable by line) and call `ingest(...,
   startingLine = startingLine)`.
4. After the stream is exhausted (not stopped early by a `Rollback`),
   commit a final small transaction setting
   `FileInventory.status = FileInventoryStatus.COMPLETED`. This replaces
   the existing standalone `markComplete` call site — `Main.scala` never
   actually calls `markComplete` today, so this closes that gap as a side
   effect rather than as separate new scope.

### Consequence: `Main.scala`'s job loop

`Main.scala:102-116` currently wraps the whole
`ingestor.ingestFileFromInventory(...)` call in one `transact(...)` and
then calls `jobManager.completeJob(...)` inside that same block. Since
`ingestFileFromInventory` now manages its own transactions, that outer
`transact(...)` is removed from around the `ingestFileFromInventory` call;
`jobManager.completeJob(...)` (which is genuinely `Txn[Unit]` and needs an
ambient `DbTx`) gets its own separate `transact(transactor) { ... }` block
afterward.

## Scope

**In scope:** the batching/checkpoint/resume mechanism described above,
threading it through `Ingestor`, `FileInventory`, and `Main.scala`'s
ingestion job loop.

**Out of scope:**

- Automatic retry/orchestration for a file that stopped mid-way (e.g. a
  poller that finds stale `PROCESSING` files and resubmits them as jobs).
  This design makes resuming *possible* and *correct* when `ingest` is
  called again for the same file; deciding *when* to call it again after
  a real crash is existing job-orchestration territory
  (`JobManager.scala`, which already has known gaps flagged inline —
  `// todo I think the txn is rolled back at this point :(`,
  `// todo need to handle idempotency (in all services actually)`) and is
  not part of this change.
- Byte-range file chunking (considered and rejected above).
- Making `Ledger.recordMovements`'s balance update conflict-aware. Not
  needed here: the per-batch-transaction checkpoint design guarantees a
  given line is never reprocessed once its batch has committed, so the
  double-counting risk described in "Problem" above cannot occur through
  the resume path this design adds. It remains a latent risk only if
  `ingest` were ever called a second time over a line range that was
  already committed by some *other* means (outside this design's control)
  — worth knowing about, not worth fixing here.

## Test impact

**Unaffected:** existing `IngestorSuite`/`ReconcilerSuite` tests. Their
inputs are small enough to fit in a single batch/transaction either way,
and they don't rely on the outer-`rollBackOnly`-wraps-everything assumption
in a way this change breaks.

**New tests**, using real commits (no savepoints) and explicit teardown
(delete the rows they created) instead of relying on an enclosing
`rollBackOnly`:

- A batch commits and is durable even if a later batch fails: ingest a
  small multi-batch input with `onBatchComplete` returning `Rollback` on
  the last batch, then verify (in a fresh, separate transaction) that
  earlier batches' movements and balance updates are visible, the last
  batch's are not, and `FileInventory.lastProcessedLine` reflects only the
  committed batches.
- Resuming picks up where it left off: run the above, then call `ingest`
  again with `startingLine` derived from the first call's
  `IngestionResult.lastProcessedLine`, and confirm the previously-skipped
  rows get processed exactly once (no duplicate movements, correct final
  balance) and none of the already-committed rows are reprocessed.

**Existing test requiring an update:**
`LargeFileIngestionReconciliationSuite`
(`backend/src/test/scala/perfectpair/domain/service/LargeFileIngestionReconciliationSuite.scala`)
currently wraps its whole call to `ingestor.ingest(...)` in
`transact(transactor): rollBackOnly: ...`. Once `Ingestor` manages its own
per-batch transactions internally (on its own connection, via its own
`Transactor`), that outer `rollBackOnly` no longer provides real isolation
for the ingestion calls — they commit for real on their own connection
regardless of what the outer transaction does. This test must be updated
to explicitly delete the rows/accounts/file-inventory records it created
(or otherwise reset the affected accounts) at the end, rather than relying
on rollback. This is a direct, necessary consequence of this change, not
optional cleanup.

This has a second, correctness-level consequence beyond cleanup: `DataSetup()`
(which creates the `reconAccount`/`journalAccount`/`reportAccount` and the
`MovementSpec`s the test ingests against) can no longer be constructed
inside an uncommitted outer `rollBackOnly` transaction either. Under
Postgres's default read-committed isolation, a row inserted by one
connection isn't visible to a different connection until it commits — and
`Ingestor`'s per-batch commits run on their own connection via its own
`Transactor`. If `DataSetup()`'s accounts are still sitting in an
uncommitted outer transaction when `Ingestor` starts its first batch,
`Ledger.recordMovements`'s `accountRepo.findByIdForUpdate(...)` won't find
them and will throw `NotFoundError`. So `DataSetup()` must be committed
(e.g. its own `transact(transactor) { DataSetup() }` call, no
`rollBackOnly`) before `ingest` is called — and its rows become part of
what the test's teardown has to clean up.

## Out-of-scope-but-adjacent findings

- `JobManager.scala:34`'s `// todo I think the txn is rolled back at this
  point :(` comment describes exactly the class of problem this design
  solves for ingestion specifically, but the comment is about
  `submitAndProcessJob`, a more general helper not used by the ingestion
  job loop today. Left alone.
