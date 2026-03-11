package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Movement, Relationship}
import perfectpair.domain.port.driven.Txn

type MovementInID  = String
type MovementOutID = String

trait MovementRepo extends DomainRepo[Movement, Movement]:
  def findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]]
  def findByAccount(accountID:             ID): Txn[Vector[Movement]]
  def findEqualityMatches(
    sqlCriteria:  String,
    relationship: Relationship
  ): Txn[Vector[(MovementInID, MovementOutID)]]
