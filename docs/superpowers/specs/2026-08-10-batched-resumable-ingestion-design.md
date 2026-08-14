# Batched, resumable ingestion — design

Date: 2026-08-10

## Problem

`Ingestor.ingest` (`backend/src/main/scala/onparity/domain/service/Ingestor.scala:48`)
is `Txn[IngestionResult]` — a context function requiring an ambient `DbTx`
(`type Txn[T] = DbTx ?=> T`, `backend/src/main/scala/onparity/domain/port/driven/package.scala:6`).
The transaction boundary is established entirely by the caller. In
production, that caller is the ingestion job loop in
`backend/src/main/scala/onparity/app/Main.scala:104-116`, which wraps
the *entire* `ingestor.ingestFileFromInventory(...)` call — every batch,
for a 5,000,000-row file that's ~50,000 batches of 100 — in one
`transact(...)` block. The transaction stays open for the whole file, and
if the process crashes partway through, nothing is committed and the next
attempt has to reprocess the file from line one.

There's also a latent correctness issue this surfaces: `Ledger.recordMovements`
(`backend/src/main/scala/onparity/domain/service/Ledger.scala:40-43`)
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
assume one contiguous line count per file: `onparity.domain.service.Ingestor.scala:76-80`).
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

### A new method, not a rewrite of `ingest`

`ingest(movementSpec, fileSourceEntry, is): Txn[IngestionResult]` stays
exactly as it is today — same signature, same ambient-transaction
behavior, same idempotency-key formula (`lineCounter` over accepted
lines). Every existing caller and test keeps working unchanged.

The batching/checkpoint/resume behavior lives in a new method,
`ingestResumable`, added alongside it. The two are kept fully separate
(no shared parsing helper) specifically so nothing about `ingest`'s
existing behavior — including its idempotency-key formula — can change
as a side effect of this work: reusing a shared helper that also serves
`ingestResumable`'s checkpoint-based key formula would mean changing
`ingest`'s keys too, which would break dedup for any file re-ingested
through `ingest` after this change (old rows keyed by the old formula,
a new attempt using a new formula would no longer collide with them via
`ON CONFLICT`, producing duplicates). A small amount of duplicated
parsing logic between the two methods is the deliberate, safer trade-off.

`ingestFileFromInventory` (the production, job-driven path) switches from
calling `ingest` to calling `ingestResumable`; nothing else changes what
it calls `ingest`.

### Checkpoint = raw line index, unified with the idempotency key

`ingestResumable` needs one counter, not `ingest`'s two (the raw
`zipWithIndex` line number, used only to detect the header row today, and
a separately incremented `lineCounter` used to build the idempotency key
over accepted lines only). This design collapses them into one, for
`ingestResumable` specifically: **the raw `zipWithIndex` line index is
used both as the checkpoint value and as the idempotency-key input.**
Concretely, the raw index is carried through the filter step instead of
being dropped:

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

### `ingestResumable` owns its own transactions

`Ingestor` gains a `Transactor` dependency (added to its existing `using`
clause alongside `ledger`, `movementRepo`, etc.), used only by
`ingestResumable`. Unlike `ingest`, `ingestResumable` is a plain method,
not `Txn[T]` — it calls `transact(transactor) { ... }` itself, once per
batch. Each batch's transaction contains, atomically:

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

`ingestResumable`'s signature:

```scala
case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)
enum BatchDecision:
  case Commit, Rollback

def ingestResumable(
  movementSpec:    MovementSpec,
  fileSourceEntry: FileSourceEntry,
  is:              InputStream,
  startingLine:    Long                        = 0,
  batchSize:       Int                         = 100,
  onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
): IngestionResult
```

`IngestionResult` (already shared with `ingest`, which keeps populating it
the same way it does today) gains a `lastProcessedLine: Option[Long]`
field (the checkpoint after this call — `None` if no batch committed,
always `None` when produced by `ingest`) so a caller that isn't going
through `FileInventory` at all (e.g. a unit test calling `ingestResumable`
directly) still has what it needs to resume manually:
`ingestResumable(..., startingLine = result.lastProcessedLine.map(_ + 1).getOrElse(0))`.

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
- `ingestResumable` stops immediately, as if the process had crashed at
  that point. It does not attempt further batches.
- The checkpoint remains at the last *committed* batch (the rolled-back
  batch's checkpoint update never took effect).

A resume test then calls `ingestResumable` again (or
`ingestFileFromInventory`) and asserts it picks up from the last committed
checkpoint rather than reprocessing from line 0.

### Resuming a file

`ingestFileFromInventory` (`Ingestor.scala:28`) changes to:

1. Look up `FileInventory` and `MovementSpec` in one short, separate
   `transact(transactor) { ... }` block (a plain read — these lookups are
   `Txn[T]` today and still need an ambient `DbTx` for that one block, but
   `ingestFileFromInventory` as a whole is no longer itself `Txn[T]`: it
   now calls `ingestResumable`, which owns its own transactions rather
   than borrowing the caller's).
2. Compute `startingLine = fileInventory.lastProcessedLine.map(_ + 1).getOrElse(0L)`
   (`lastProcessedLine` becomes `Option[Long] = None` on a fresh
   `FileInventory`, distinguishing "nothing processed yet" from "processed
   through line 0").
3. Open the file via `fileSource.getFile(fileSourceEntry)` (unchanged —
   still a full re-read from the start of the stream; only the in-memory
   `.drop(startingLine)` skips already-processed lines, since plain
   `InputStream`s aren't seekable by line) and call `ingestResumable(...,
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
  This design makes resuming *possible* and *correct* when `ingestResumable`
  is called again for the same file; deciding *when* to call it again after
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
  `ingestResumable` were ever called a second time over a line range that
  was already committed by some *other* means (outside this design's
  control) — worth knowing about, not worth fixing here. `ingest` itself
  is untouched and carries whatever risk it already carried before this
  change, unchanged.

## Test impact

**Unaffected:** existing `IngestorSuite`/`ReconcilerSuite` tests, and
every other existing caller of `ingest`. `ingest` itself doesn't change —
same signature, same ambient-transaction behavior, same idempotency-key
formula — so nothing that calls it needs to change. This was the reason
for adding `ingestResumable` as a separate method rather than rewriting
`ingest` in place: doing the latter would have broken every existing
`IngestorSuite` test, since they all construct `DataSetup()` inside an
outer `rollBackOnly` block immediately before calling `ingest`, and a
rewritten `ingest` opening its own separate connection per batch wouldn't
be able to see those still-uncommitted accounts.

**New tests**, using real commits (no savepoints) and explicit teardown
(delete the rows they created) instead of relying on an enclosing
`rollBackOnly`:

- A batch commits and is durable even if a later batch fails: call
  `ingestResumable` on a small multi-batch input with `onBatchComplete`
  returning `Rollback` on the last batch, then verify (in a fresh,
  separate transaction) that earlier batches' movements and balance
  updates are visible, the last batch's are not, and
  `FileInventory.lastProcessedLine` reflects only the committed batches.
- Resuming picks up where it left off: run the above, then call
  `ingestResumable` again with `startingLine` derived from the first
  call's `IngestionResult.lastProcessedLine`, and confirm the
  previously-skipped rows get processed exactly once (no duplicate
  movements, correct final balance) and none of the already-committed
  rows are reprocessed.

These new tests, and `LargeFileIngestionReconciliationSuite`
(`backend/src/test/scala/onparity/domain/service/LargeFileIngestionReconciliationSuite.scala`,
which switches from calling `ingest` to calling `ingestResumable` since
it's specifically exercising the large/batched path) all need:

- Explicit teardown (delete the rows they created) instead of relying on
  an enclosing `rollBackOnly`, since `ingestResumable`'s batches commit
  for real on their own connection regardless of what any outer
  transaction does.
- `DataSetup()` committed on its own — e.g. `transact(transactor) {
  DataSetup() }`, no `rollBackOnly` — *before* calling `ingestResumable`.
  Under Postgres's default read-committed isolation, a row inserted by
  one connection isn't visible to a different connection until it
  commits, and `ingestResumable`'s batches run on their own connection.
  If `DataSetup()`'s accounts were still sitting in an uncommitted outer
  transaction when `ingestResumable` starts its first batch,
  `Ledger.recordMovements`'s `accountRepo.findByIdForUpdate(...)` would
  throw `NotFoundError`. `DataSetup()`'s rows become part of what each
  test's teardown has to clean up too.

## Out-of-scope-but-adjacent findings

- `JobManager.scala:34`'s `// todo I think the txn is rolled back at this
  point :(` comment describes exactly the class of problem this design
  solves for ingestion specifically, but the comment is about
  `submitAndProcessJob`, a more general helper not used by the ingestion
  job loop today. Left alone.
