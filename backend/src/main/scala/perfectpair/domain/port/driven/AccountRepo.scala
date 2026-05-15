package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model
import perfectpair.domain.model.*

trait AccountRepo extends DomainRepo[Account, Account]:

  def findByFundFlowAndAccountName(fundFlowID:  ID, accountName: String): Txn[Option[Account]]
  def findByFundFlow(fundFlowID:                ID): Txn[Vector[Account]]
  def findNominalWithNonZeroBalance(fundFlowID: ID): Txn[Vector[Account]]
  def findAll(): Txn[Vector[Account]]
  def isReconAccount(accountID: ID): Txn[Boolean]
