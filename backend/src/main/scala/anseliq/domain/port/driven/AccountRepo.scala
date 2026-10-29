package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.*

trait AccountRepo extends DomainRepo[Account, Account]:

  def findByIdForUpdate(id:                   ID): Txn[Option[Account]]
  def findByLedgerAndAccountName(ledgerId:    ID, accountName: String): Txn[Option[Account]]
  def findByLedger(ledgerId:                  ID): Txn[Vector[Account]]
  def findNominalWithNonZeroBalance(ledgerId: ID): Txn[Vector[Account]]
  def findAll(): Txn[Vector[Account]]
