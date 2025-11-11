package com.eweise.perfectpair.domain.service

import com.augustnagro.magnum.DbCon
import com.eweise.perfectpair.domain.model.{FundFlowConfig, ID}
import com.eweise.perfectpair.domain.port.driven.FundFlowConfigRepo

class FundFlowCache(fundFlowConfigRepo: FundFlowConfigRepo):
  private var configsByName: Map[String, FundFlowConfig] = Map.empty
  private var configsByID: Map[ID, FundFlowConfig]       = Map.empty

  def init()(using db: DbCon): Unit =
    val ffConfigs = fundFlowConfigRepo.findAll
    configsByName = ffConfigs.view.map(f => f.name -> f).toMap
    configsByID = ffConfigs.view.map(f => f.id -> f).toMap

  def getConfigByName(name: String): Option[FundFlowConfig] = configsByName.get(name)

  def getConfigByID(id: ID): Option[FundFlowConfig] = configsByID.get(id)
