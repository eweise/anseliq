package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class FundFlowService(fundFlowConfigRepo: FundFlowConfigRepo):

  def createFundFlow(fundFlow: FundFlowConfig): Txn[FundFlowConfig] =
    fundFlowConfigRepo.insert(fundFlow)
    fundFlow

  def updateFundFlow(id: ID, name: String, dailyCutoffHours: Int): Txn[FundFlowConfig] =
    val existing = fundFlowConfigRepo.findById(id).ErrIfNotFound("fund flow", id)
    val updated  = existing.copy(name = name, dailyCutoffHours = dailyCutoffHours)
    fundFlowConfigRepo.update(updated)
    updated
