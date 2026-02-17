package perfectpair.domain.port.driven

import com.augustnagro.magnum.DbCon
import perfectpair.common.model.model.ID
import perfectpair.domain.model.Movement
import perfectpair.domain.port.driven.{MovementRepo, Txn}

trait MovementRepo extends DomainRepo[Movement, Movement]:
  def findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]]
  def findByAccount(accountID:             ID): Txn[Vector[Movement]]
  def findEqualityMatches(
    sqlCriteria: String
  ): Txn[Vector[(String, String)]]
