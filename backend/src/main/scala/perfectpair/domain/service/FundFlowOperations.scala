package perfectpair.domain.service

import perfectpair.adapter.driving.Payload
import perfectpair.common.model.FundFlowPayload
import perfectpair.domain.model.ErrIfNotFound
import perfectpair.domain.port.driven.{FundFlowConfigRepo, Txn}

import java.util.UUID

class FundFlowOperations(using
  val fundFlowConfigRepo: FundFlowConfigRepo
):
  def createFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    val fundFlow = Payload.fundFlowPayloadToDomain(payload)
    fundFlowConfigRepo.insert(fundFlow)
    Payload.fundFlowPayloadFromDomain(fundFlow)

  def updateFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    if payload.id.isEmpty then IllegalArgumentException("Fund flow ID is required")

    val ffID       = UUID.fromString(payload.id.get)
    var existingFF = fundFlowConfigRepo.findById(ffID).ErrIfNotFound("fund flow", ffID)
    existingFF = existingFF.copy(name = payload.name)
    fundFlowConfigRepo.update(existingFF)
    Payload.fundFlowPayloadFromDomain(existingFF)
