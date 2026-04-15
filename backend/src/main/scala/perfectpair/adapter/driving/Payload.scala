package perfectpair.adapter.driving

import perfectpair.common.model.*
import perfectpair.domain.model.*
import upickle.default.write as upickleWrite

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

  def movementSpecPayloadFromDomain(spec: MovementSpec): MovementSpecPayload =
    MovementSpecPayload(
      id             = Some(spec.id.toString),
      name           = spec.name,
      inAccountId    = spec.inAccountId.toString,
      outAccountId   = spec.outAccountId.toString,
      dataSourceJson = spec.dataSourceJson
    )

  def reconSpecPayloadFromDomain(spec: ReconciliationSpec): ReconciliationSpecPayload =
    ReconciliationSpecPayload(
      id                = Some(spec.id.toString),
      accountId         = Some(spec.accountId.toString),
      description       = spec.description,
      agingLimit        = spec.agingLimit,
      relationship      = spec.relationship.toString,
      inMovementSpecId  = spec.inMovementSpecId.toString,
      outMovementSpecId = spec.outMovementSpecId.toString,
      rule              = spec.rule()
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
