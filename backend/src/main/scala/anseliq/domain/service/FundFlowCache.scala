package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.FundFlowConfig
import anseliq.domain.port.driven.FundFlowConfigRepo
import com.augustnagro.magnum.DbCon

class FundFlowCache(fundFlowConfigRepo: FundFlowConfigRepo):
  private var configsByName: Map[String, FundFlowConfig] = Map.empty
  private var configsByID:   Map[ID, FundFlowConfig]     = Map.empty

  def init()(using db: DbCon): Unit =
    val ffConfigs = fundFlowConfigRepo.findAll
    configsByName = ffConfigs.view.map(f => f.name -> f).toMap
    configsByID   = ffConfigs.view.map(f => f.id -> f).toMap

//  def getConfigByName(name: String): Option[FundFlowConfig] = configsByName.get(name)
//
//  def getConfigByID(id: ID): Option[FundFlowConfig] = configsByID.get(id)
//
//  def getAll: Iterable[FundFlowConfig] = configsByName.values
