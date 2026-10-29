package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class LedgerService(ledgerRepo: LedgerRepo):

  def createLedger(ledger: Ledger): Txn[Ledger] =
    ledgerRepo.insert(ledger)
    ledger

  def updateLedger(id: ID, name: String, dailyCutoffHours: Int): Txn[Ledger] =
    val existing = ledgerRepo.findById(id).ErrIfNotFound("ledger", id)
    val updated  = existing.copy(name = name, dailyCutoffHours = dailyCutoffHours)
    ledgerRepo.update(updated)
    updated
