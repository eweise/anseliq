package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.{Movement, MovementSearchCriteria, Relationship}
import onparity.domain.port.driven.Txn

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

  def search(criteria: MovementSearchCriteria): Txn[Vector[Movement]]

  // Used by Ledger.recordMovements to exclude already-recorded movements (matched by the
  // deterministic, content-derived referenceId) from the batch *before* computing account balance
  // deltas — the movement insert itself is already deduped via ON CONFLICT(reference_id) DO NOTHING,
  // but that alone doesn't stop a retried/redelivered batch from double-counting account balances.
  def findExistingReferenceIds(referenceIds: Iterable[ID]): Txn[Set[ID]]
