package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{Movement, MovementSearchCriteria, Relationship}
import anseliq.domain.port.driven.Txn

import javax.sql.DataSource

type MovementInID  = String
type MovementOutID = String

/**
 * A server-side cursor over one movement_spec's unreconciled movements, pre-sorted once by the
 * match-key expression given to withUnreconciledCursor — unlike findUnreconciledPage, which
 * re-sorts the full remaining unreconciled set on every page. Only valid for the lifetime of the
 * withUnreconciledCursor call that produced it.
 */
trait UnreconciledCursor:
  /** Returns the next page, or an empty Vector once the cursor is exhausted. */
  def fetchPage(): Vector[Movement]

trait MovementRepo extends DomainRepo[Movement, Movement]:
  def findUnReconciledByAccount(accountID:           ID): Txn[Vector[Movement]]
  def countUnreconciledByAccount(accountID:          ID): Txn[Int]
  def countByAccount(accountID:                      ID): Txn[Int]
  def findUnReconciledByMovementSpec(movementSpecID: ID): Txn[Vector[Movement]]
  def findByAccount(accountID:                       ID): Txn[Vector[Movement]]
  def findByReconciliationID(reconciliationID:       ID): Txn[Vector[Movement]]
  def findEqualityMatches(
    sqlCriteria:       String,
    inMovementSpecId:  ID,
    outMovementSpecId: ID,
    relationship:      Relationship
  ): Txn[Vector[(MovementInID, MovementOutID)]]
  def findUnreconciledPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  ): Txn[Vector[Movement]]
  def findUnreconciledByExactMatchKey(
    movementSpecId: ID,
    matchKeyExpr:   String,
    keyValue:       String
  ): Txn[Vector[Movement]]

  /**
   * Opens a WITH HOLD cursor over movementSpecId's unreconciled movements, sorted once by
   * matchKeyExpr (afterKey applied as a lower bound before that one sort, same semantics as
   * findUnreconciledPage's afterKey, for resuming from a checkpoint without re-scanning already
   * consumed rows), and runs `use` against it. Not a Txn: it manages its own dedicated connection
   * from dataSource for the duration of `use`, independent of any transact(...) call — the cursor
   * and its connection are always closed when `use` returns, including on exception.
   */
  def withUnreconciledCursor[T](
    dataSource:     DataSource,
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  )(use: UnreconciledCursor => T): T

  def setReconciliationIds(idPairs: Iterable[(ID, ID)]): Txn[Unit]

  // Exact match on the same meta_data.file_source_entry tag Ingestor writes on every movement — see
  // movement_meta_data_index in V1__initial_schema.sql, which this needs to stay index-backed rather
  // than a full table scan. Unlike search's fileSourceQuery (ILIKE, capped at 1000), this has no cap:
  // it's meant to find every movement from one specific file, e.g. for
  // ManualReconciler.bulkUnreconcileByFile.
  def findByFileSourceEntry(fileSourceEntry: String): Txn[Vector[Movement]]

  def search(criteria: MovementSearchCriteria): Txn[Vector[Movement]]

  // Used by Bookkeeper.recordMovements to exclude already-recorded movements (matched by the
  // deterministic, content-derived referenceId) from the batch *before* computing account balance
  // deltas — the movement insert itself is already deduped via ON CONFLICT(reference_id) DO NOTHING,
  // but that alone doesn't stop a retried/redelivered batch from double-counting account balances.
  def findExistingReferenceIds(referenceIds: Iterable[ID]): Txn[Set[ID]]
