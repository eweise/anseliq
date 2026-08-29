package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.{Movement, MovementSearchCriteria, Relationship}
import onparity.domain.port.driven.Txn

type MovementInID  = String
type MovementOutID = String

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
  def setReconciliationIds(idPairs: Iterable[(ID, ID)]): Txn[Unit]

  def search(criteria: MovementSearchCriteria): Txn[Vector[Movement]]

  // Used by Ledger.recordMovements to exclude already-recorded movements (matched by the
  // deterministic, content-derived referenceId) from the batch *before* computing account balance
  // deltas — the movement insert itself is already deduped via ON CONFLICT(reference_id) DO NOTHING,
  // but that alone doesn't stop a retried/redelivered batch from double-counting account balances.
  def findExistingReferenceIds(referenceIds: Iterable[ID]): Txn[Set[ID]]
