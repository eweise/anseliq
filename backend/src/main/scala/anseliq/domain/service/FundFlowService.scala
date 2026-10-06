package anseliq.domain.service

import anseliq.adapter.driving.Payload
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

import java.util.UUID

class FundFlowService(fundFlowConfigRepo: FundFlowConfigRepo):

  def createFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    val fundFlow = Payload.fundFlowPayloadToDomain(payload)
    fundFlowConfigRepo.insert(fundFlow)
    Payload.fundFlowPayloadFromDomain(fundFlow)

  def updateFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    if payload.id.isEmpty then IllegalArgumentException("Fund flow ID is required")
    val ffID       = UUID.fromString(payload.id.get)
    var existingFF = fundFlowConfigRepo.findById(ffID).ErrIfNotFound("fund flow", ffID)
    existingFF = existingFF.copy(name = payload.name, dailyCutoffHours = payload.dailyCutoffHours)
    fundFlowConfigRepo.update(existingFF)
    Payload.fundFlowPayloadFromDomain(existingFF)
