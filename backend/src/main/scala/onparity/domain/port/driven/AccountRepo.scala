package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.*

trait AccountRepo extends DomainRepo[Account, Account]:

  def findByIdForUpdate(id:                     ID): Txn[Option[Account]]
  def findByFundFlowAndAccountName(fundFlowID:  ID, accountName: String): Txn[Option[Account]]
  def findByFundFlow(fundFlowID:                ID): Txn[Vector[Account]]
  def findNominalWithNonZeroBalance(fundFlowID: ID): Txn[Vector[Account]]
  def findAll(): Txn[Vector[Account]]
