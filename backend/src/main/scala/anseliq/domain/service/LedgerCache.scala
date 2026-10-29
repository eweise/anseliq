package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.Ledger
import anseliq.domain.port.driven.LedgerRepo
import com.augustnagro.magnum.DbCon

class LedgerCache(ledgerRepo: LedgerRepo):
  private var configsByName: Map[String, Ledger] = Map.empty
  private var configsByID:   Map[ID, Ledger]     = Map.empty

  def init()(using db: DbCon): Unit =
    val ffConfigs = ledgerRepo.findAll
    configsByName = ffConfigs.view.map(f => f.name -> f).toMap
    configsByID   = ffConfigs.view.map(f => f.id -> f).toMap

//  def getConfigByName(name: String): Option[Ledger] = configsByName.get(name)
//
//  def getConfigByID(id: ID): Option[Ledger] = configsByID.get(id)
//
//  def getAll: Iterable[Ledger] = configsByName.values
