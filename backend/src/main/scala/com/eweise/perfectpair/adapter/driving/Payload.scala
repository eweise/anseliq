package com.eweise.perfectpair.adapter.driving

import com.eweise.perfectpair.common.model.*
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.AccountType.{Real, Recon}
import upickle.default.*

object Payload:

  def fundFlowPayloadFromDomain(ff: FundFlowConfig): FundFlowPayload =
    FundFlowPayload(
      id                = ff.id.toString,
      name              = ff.name,
      realAccountSpecs  = ff.fundFlow.realAccountSpecs.map(realAccountSpecPayloadFromDomain),
      reconAccountSpecs = ff.fundFlow.reconAccountSpecs.map(reconAccountSpecPayloadFromDomain),
      movementSpecs     = Seq(),
      createdAt         = ff.createdAt,
      updatedAt         = ff.updatedAt
    )

  def fundFlowSpecPayloadToDomain(ff: FundFlowPayload): FundFlowSpec =
    FundFlowSpec(
      realAccountSpecs  = ff.realAccountSpecs.map(realAccountSpecPayloadToDomain),
      reconAccountSpecs = ff.reconAccountSpecs.map(reconAccountSpecPayloadToDomain),
      movementSpecs     = Seq()
    )

  def realAccountSpecPayloadFromDomain(spec: RealAccountSpec): RealAccountSpecPayload =
    RealAccountSpecPayload(name = spec.name, properties = spec.properties)

  def realAccountSpecPayloadToDomain(a: RealAccountSpecPayload): RealAccountSpec =
    RealAccountSpec(
      name       = a.name,
      properties = a.properties
    )

  def reconAccountSpecPayloadFromDomain(spec: ReconciliationAccountSpec): ReconAccountSpecPayload =
    ReconAccountSpecPayload(
      name       = spec.name,
      matcher    = spec.matcher,
      agingLimit = spec.agingLimit,
      properties = spec.properties
    )

  def reconAccountSpecPayloadToDomain(r: ReconAccountSpecPayload): ReconciliationAccountSpec =
    ReconciliationAccountSpec(
      name       = r.name,
      matcher    = r.matcher,
      agingLimit = r.agingLimit,
      properties = r.properties
    )

  def fundFlowPayloadToDomain(payload: FundFlowPayload): FundFlowConfig =
    // fixme need to convert payload to domain object and then write to fundFlowSon
    FundFlowConfig(name = payload.name, fundFlowJson = write(payload))

  def accountPayloadfromDomainModel(dmAccount: Account): AccountPayload =
    AccountPayload(
      id          = dmAccount.id.toString,
      name        = dmAccount.name,
      fundFlowId  = dmAccount.fundFlowId,
      accountType = accountTypeFromModel(dmAccount.accountType),
      balance     = dmAccount.balance
    )

  def accountTypeFromModel(at: AccountType): AccountTypePayload =
    at match
      case Real  => AccountTypePayload.Real
      case Recon => AccountTypePayload.Recon

  def movementPayloadfromDomainModel(dmMovement: Movement): MovementPayload =
    MovementPayload(
      id               = dmMovement.id.toString,
      referenceId      = dmMovement.referenceId.toString,
      outAccountId     = dmMovement.outAccountId.toString,
      inAccountId      = dmMovement.inAccountId.toString,
      amount           = dmMovement.amount,
      properties       = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull,
      status           = dmMovement.status.toString
    )
