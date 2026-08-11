# DB performance tuning notes

Local `recon_db` was slow on large ingestion/reconciliation runs. These changes fixed it,
in order of impact. The schema changes live in `backend/db/schema.sql` and apply automatically;
the `postgresql.conf` changes are host-level and must be reapplied manually per environment.

## Schema changes (`backend/db/schema.sql`)

- `movement_unreconciled_by_spec` — partial index `(movement_spec_id) WHERE reconciliation_id IS NULL`,
  matching the `WHERE` clause `findUnreconciledPage`/`findUnreconciledByExactMatchKey` actually use.
  Without it, reconciliation re-scans every historical row for a spec on every page and filters out
  already-reconciled ones; with it, cost tracks only what's actually left. Measured ~5x faster
  execution and ~700x fewer heap blocks touched at 95% pre-reconciled (the realistic late-stage
  state of a big run).
- `movement_props_index` — `WITH (fastupdate = false)`. GIN's `fastupdate` buffers new entries in a
  pending list and periodically flushes them via an expensive synchronous merge, whose cost grows
  with index size — this produced ingestion's "fast, then a long stall" pattern. Ingestion already
  batches thousands of rows per transaction (`Ledger.recordMovements`), so `fastupdate`'s intended
  benefit (amortizing cost across many small individual inserts) doesn't apply here. Measured 3.7x
  faster total ingestion time on a 1M-row synthetic run, with worst-case batch latency dropping from
  1.6s (a merge stall) to under 100ms.

## `postgresql.conf` changes (host-level, not version-controlled)

Applied to `/Library/PostgreSQL/18/data/postgresql.conf`, restart required for `shared_buffers`:

| Setting | Default | Set to | Why |
|---|---|---|---|
| `shared_buffers` | 128MB | `4GB` | Postgres's own cache; `movement` alone (table + indexes) is multiple GB. At 128MB, most reads were falling through to disk (`DataFileRead` waits observed live, ~46% cache hit ratio). |
| `work_mem` | 4MB | `128MB` | `findUnreconciledPage`'s `ORDER BY` on the JSONB match key isn't index-backed (the key is admin-configurable per reconciliation spec, so it can't be), so it sorts the full remaining unreconciled set on every page. At 4MB this spilled to disk (`Sort Method: external merge Disk: 52MB` observed via `EXPLAIN ANALYZE`). Applies per sort/hash operation per connection — don't raise further without checking concurrent connection count. |
| `random_page_cost` | 4 (HDD-tuned) | `1.1` | Local disk is an SSD (`APPLE SSD AP0512Z`); the HDD-tuned default over-penalizes index scans in the planner's cost model relative to actual hardware. |
| `max_wal_size` | 1GB | `2GB` | Reduces checkpoint frequency under sustained write load from large ingestion runs. |

Not changed, but considered: `synchronous_commit = off` would speed up the many-small-transaction
batch-commit pattern further, at the cost of losing the last fraction-of-a-second of commits on a
crash. Left `on` since it's a durability tradeoff rather than a free win — revisit if ingestion
speed is still a bottleneck after the above.

## Reapplying on a new environment

1. `make create-db` picks up the schema changes automatically from `backend/db/schema.sql`.
2. Manually set the four `postgresql.conf` values above and restart the Postgres service.
