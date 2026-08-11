# Streaming, resumable reconciliation — design

Date: 2026-08-11

## Problem

`InMemoryReconciler.reconcileAccount`
(`backend/src/main/scala/perfectpair/domain/service/InMemoryReconciler.scala:39`)
is the reconciliation implementation actually wired into production
(`Main.scala:44`, `Routes.scala:208`) — the other reconciliation
implementation, `Reconciler` (`backend/src/main/scala/perfectpair/domain/service/Reconciler.scala`),
is unused by the running app (only exercised by tests) and has already
been marked `@deprecated`.

For each `ReconciliationSpec` on the account, `reconcileAccount`:

1. Calls `getUnreconciledMovements` twice (once per side), which runs
   `movementRepo.findUnReconciledByMovementSpec` — a SQL query that
   fetches *every* unreconciled `Movement` for that movement spec into a
   fully-materialized `Vector[Movement]`
   (`MovementRepoLive.scala:56-63`; it does have `ORDER BY created_at`,
   but that ordering is discarded — it's not the ordering matching
   needs).
2. Calls `doSort` (`InMemoryReconciler.scala:251-260`) on each side,
   sorting the *entire* in-memory `Seq[Movement]` in the JVM by the
   rule's actual match key (e.g. a `properties` JSON field).
3. Runs a two-pointer merge-join over the two fully-sorted, fully
   in-memory sequences to find matches.
4. Writes each match (or, for `OneToMany`, each group) as its own
   `Reconciliation` insert plus `ledger.reconcile` call — all within one
   ambient transaction for the entire `reconcileAccount` call.

For an account with millions of unreconciled movements on either side,
step 1 pulls millions of rows into heap, step 2 sorts them all in the
JVM, and step 4 holds one transaction open for however long it takes to
write every match — the same "transaction held open too long" shape
already fixed for ingestion, plus a memory problem ingestion never had
(its `Flow`-based read was already streaming).

## Scope

**In scope:** `InMemoryReconciler` only.

**Out of scope:** the deprecated `Reconciler` class (left alone, per
existing `@deprecated` marker); `ManyToOne`/`ManyToMany` relationships
(both are unimplemented no-ops today —
`InMemoryReconciler.scala:143-144` — and stay that way, unaffected by
this change either way); automatic retry/orchestration for a crashed
reconciliation job — same boundary as the ingestion design: this makes
resuming *possible* and *correct*, not automatic; true database
server-side cursors (keyset pagination chosen instead — see below);
further SQL-level batching of reconciliation writes beyond the
already-available `insertAll`/`updateAll` bulk methods on `DomainRepo`
(e.g. a single multi-row UPSERT) — a valid future optimization, not
required to fix the scale problem.

## Design

### A new method, not a rewrite of `reconcileAccount`

Same reasoning as `ingest`/`ingestResumable`: `reconcileAccount` has
several existing callers beyond production —
`EndToEndSuite.scala` (3 call sites), `InmemoryReconcilerSuite.scala`
(6 call sites), `ManualReconcilerSuite.scala` (1 call site) — all of
which construct their test data inside an outer `rollBackOnly`
transaction immediately before calling it. Rewriting `reconcileAccount`
to manage its own per-batch transactions would break all of them, for
the identical reason rewriting `ingest` would have.

`reconcileAccount` stays exactly as it is today. A new method,
`reconcileAccountResumable`, is added alongside it. Production call
sites switch to it:

- `Main.scala:66`'s async Reconciliation job poller.
- `Routes.scala:208`'s manual HTTP reconcile-account endpoint.

Nothing else that calls `reconcileAccount` needs to change.

### Streaming replaces in-memory sort

`getUnreconciledMovements` + `doSort` are replaced, for
`reconcileAccountResumable` only, by a query that does the ordering in
Postgres and pages through results:

```sql
SELECT ...
FROM movement
WHERE reconciliation_id IS NULL
  AND movement_spec_id = :movementSpecId
  AND <matchKeyExpr> > :lastSeenKey    -- omitted on the first page
ORDER BY <matchKeyExpr>
LIMIT :pageSize
```

`<matchKeyExpr>` is the same expression `compareMovements`
(`InMemoryReconciler.scala:206-224`) already knows how to evaluate per
rule type in Scala (e.g. `properties['props']['id']` for a
`StringAttributeMatchRule`) — built once per `ReconciliationSpec` from
its `Rule`, reused as both the SQL `ORDER BY`/`WHERE` expression and the
comparison the merge-join already performs.

The merge-join algorithm itself is unchanged: the same two-pointer walk
over "in" and "out" that exists today, just fed by two lazily-paginated
queries (fetch the next page for whichever side's pointer runs off the
end of its current page) instead of two fully-materialized in-memory
sequences. Memory use is bounded by two page buffers (e.g. `pageSize`
rows per side), not by the size of the backlog.

`OneToMany`'s grouping (consecutive out-movements matching the same
in-movement get grouped into one reconciliation,
`InMemoryReconciler.scala:107-142`) needs one adjustment for paging: a
group can't be finalized until it's confirmed complete, which may
require peeking into the next page of the out-side stream (don't stop
grouping just because the current page ran out — fetch the next page
and check whether its leading rows still match the same in-movement
before finalizing).

### Batched, real commits

Every `batchSize` matches (or groups, for `OneToMany`) commit as their
own transaction — mirroring `ingestResumable`'s per-batch `transact(...)`.
`Reconciliation` inserts use `reconRepo.insertAll(...)` (a bulk method
`DomainRepo` already provides) instead of looping individual `insert`
calls. `ledger.reconcile(...)` is still called per match/group, unchanged
— it's not touched by this design (see "Out of scope" below) — so each
batch still makes up to `batchSize` calls to it, each internally looping
its own small number of movement updates. The improvement here is that
this work now happens inside a *bounded* transaction (`batchSize` matches
worth), not that every individual write within a batch is itself bulked.

`InMemoryReconciler` gains a `Transactor` dependency, exactly as
`Ingestor` did, since `reconcileAccountResumable` owns its own
transactions rather than borrowing an ambient one.

### Checkpoint: explicit, but scoped to one job attempt

Ingestion's checkpoint could persist indefinitely because a file's
contents never change. Reconciliation's backlog isn't like that: new
unreconciled movements can arrive at any time, with match keys that sort
*before* wherever a previous run left off (e.g. a late-arriving file
with older-sorting IDs). The fix isn't to avoid a checkpoint — it's to
scope it correctly: **the checkpoint is only ever used to resume the
same crashed job attempt, never to bound eligibility beyond
`reconciliation_id IS NULL`.** A fresh trigger (a new ingestion event, a
manual re-trigger) always starts with no checkpoint and re-derives the
true current backlog from `reconciliation_id IS NULL` with no lower
bound, so late arrivals are always visible to the next run regardless of
where any previous run's checkpoint left off.

Storage: `recon.job` gains a nullable `progress_json TEXT` column,
mirroring the existing `job_info_json` column
(`backend/db/schema.sql:115-124`) but updated *incrementally* as batches
commit rather than once at the end. Holds a JSON-encoded
`Map[reconSpecId, ReconciliationCheckpoint]`:

```scala
case class ReconciliationCheckpoint(lastInMatchKey: Option[String], lastOutMatchKey: Option[String])
```

`reconcileAccountResumable`'s signature:

```scala
case class SpecBatchProgress(specId: ID, matchesInBatch: Int, checkpoint: ReconciliationCheckpoint)

def reconcileAccountResumable(
  accountId:       ID,
  batchSize:       Int                                = 500,
  jobId:           Option[ID]                          = None,
  onBatchComplete: SpecBatchProgress => BatchDecision  = _ => BatchDecision.Commit
): ReconciliationResumableResult
```

When `jobId` is present, `reconcileAccountResumable` reads that job's
`progress_json` at the start (to resume each spec's pagination from
wherever it left off) and updates it, per spec, inside each batch's
transaction — the same pattern `ingestResumable` uses for
`fileInventoryID`/`FileInventory.lastProcessedLine`. `BatchDecision` and
the `Commit`/`Rollback` semantics are the same enum already introduced
for ingestion (reused, not redefined) — production always passes the
default; tests use `onBatchComplete` to simulate a crash at a chosen
batch and verify resume, the same way `IngestResumableSuite` does today.

### Consequence: `Main.scala` and `Routes.scala`

Both currently wrap their call to `reconciler.reconcileAccount(...)` in
one ambient `transact(...)` — `Main.scala:59-77`'s Reconciliation job
fork, and `Routes.scala:205-216`'s `submitAndProcessJob` call. Since
`reconcileAccountResumable` owns its own transactions, both call sites
change analogously to how `Main.scala`'s ingestion job loop changed:
`reconcileAccountResumable` is called *outside* any enclosing
`transact(...)`, and whatever job-completion bookkeeping still needs an
ambient transaction (`jobManager.completeJob(...)`) gets its own,
separate `transact(...)` afterward.

## Test impact

**Unaffected:** `EndToEndSuite`, `InmemoryReconcilerSuite`,
`ManualReconcilerSuite` — all call `reconcileAccount`, which doesn't
change.

**New tests**, using real commits and explicit teardown instead of
`rollBackOnly` (same reasons as `IngestResumableSuite`: batches commit
for real on their own connection regardless of any enclosing
transaction; `DataSetup()` must be committed on its own, before
`reconcileAccountResumable` is called, so its accounts are visible to
that separate connection):

- Streaming matching produces the same matches as the existing in-memory
  algorithm, for both `OneToOne` and `OneToMany`, including a case where
  an `OneToMany` group spans a page boundary.
- A batch commits and is durable even if a later batch fails (mirrors
  `IngestResumableSuite`'s first test): `onBatchComplete` returns
  `Rollback` on a chosen batch, earlier batches' reconciliations/movement
  updates are verified visible, the later batch's are not.
- Resuming from a job's `progress_json` picks up where it left off: no
  duplicate reconciliations, no movement matched twice, and — the
  correctness property this design exists to protect — a movement
  inserted *after* a crashed run's checkpoint but with a match key that
  sorts *before* it still gets matched by the next (fresh, no-checkpoint)
  run.

## Out-of-scope-but-adjacent findings

- `Ledger.reconcile` loops `movementRepo.update(m)` individually per
  movement, and `reconcileAccountResumable` still calls it once per
  match/group, unchanged. Batching those individual updates too (e.g.
  giving `Ledger` a bulk variant that accepts multiple
  reconciliation-groups at once) is a valid further optimization within
  each already-bounded batch, but isn't required to fix the two problems
  this design targets (unbounded memory, unbounded transaction
  duration), so it's left for later.
