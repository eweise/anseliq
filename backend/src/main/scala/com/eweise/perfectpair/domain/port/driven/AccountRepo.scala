package com.eweise.perfectpair.domain.port.driven

import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*

trait AccountRepo extends DomainRepo[Account, Account]:

  def findByFundFlowAndAccountName(fundFlowID: ID, accountName: String): Txn[Option[Account]]
  def findByFundFlow(fundFlowID: ID): Txn[Vector[Account]]
  def findNominalWithNonZeroBalance(fundFlowID: ID): Txn[Vector[model.Account]]
