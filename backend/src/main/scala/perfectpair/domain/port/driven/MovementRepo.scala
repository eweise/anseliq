package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Movement, Relationship}
import perfectpair.domain.port.driven.Txn

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
