package perfectpair.adapter.driving

import perfectpair.common.model.*
import perfectpair.domain.model.*

object Payload:

  def fundFlowPayloadToDomain(payload: FundFlowPayload): FundFlowConfig =
    FundFlowConfig(
      name = payload.name
    )

  def fundFlowPayloadFromDomain(ff: FundFlowConfig): FundFlowPayload =
    FundFlowPayload(
      id        = Some(ff.id.toString),
      name      = ff.name,
      createdAt = ff.createdAt,
      updatedAt = ff.updatedAt
    )

  def accountPayloadFromDomain(account: Account): AccountPayload =
    AccountPayload(
      fundFlowId = account.fundFlowId.toString,
      id         = Some(account.id.toString),
      name       = account.name,
      properties = account.properties.props,
      balance    = account.balance
    )

  def movementResponsefromDomainModel(dmMovement: Movement): MovementPayload =
    MovementPayload(
      id               = dmMovement.id.toString,
      referenceId      = dmMovement.referenceId.toString,
      outAccountId     = dmMovement.outAccountId.toString,
      inAccountId      = dmMovement.inAccountId.toString,
      amount           = dmMovement.amount,
      properties       = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull
    )
