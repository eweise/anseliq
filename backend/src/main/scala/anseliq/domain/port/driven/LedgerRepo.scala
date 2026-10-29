package anseliq.domain.port.driven

import anseliq.domain.model.Ledger

trait LedgerRepo extends DomainRepo[Ledger, Ledger]:
  def findByName(name: String): Txn[Option[Ledger]]
