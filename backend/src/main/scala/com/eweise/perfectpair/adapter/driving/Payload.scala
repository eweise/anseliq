package com.eweise.perfectpair.adapter.driving

import com.eweise.perfectpair.common.model.*
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.AccountType.{Real, Recon}
import upickle.default.*

import java.time.OffsetDateTime

object Payload:

  def fundFlowPayloadFromDomain(ff: model.FundFlowConfig, a: Seq[model.Account]): FundFlowPayload =
    FundFlowPayload(
      id = ff.id.toString,
      name = ff.name,
      specs = FundFlowSpecPayload(),
      createdAt = ff.createdAt.getOrElse(OffsetDateTime.now()),
      updatedAt = ff.updatedAt.getOrElse(OffsetDateTime.now())
    )

  def fundFlowSpecPayloadFromDomain(config: model.FundFlowConfig): FundFlowSpecPayload =
    FundFlowSpecPayload(
      name = config.name,
      realAccountSpecs = config.fundFlow.realAccountSpecs.map(realAccountSpecPayloadFromDomain),
      reconAccountSpecs = config.fundFlow.reconAccountSpecs.map(reconAccountSpecPayloadFromDomain),
      movementSpecs = Seq(),
      createdAt = config.createdAt,
      updatedAt = config.updatedAt,
    )

  def fundFlowSpecPayloadToDomain(ff: FundFlowSpecPayload): model.FundFlowSpec =
    model.FundFlowSpec(
      realAccountSpecs = ff.realAccountSpecs.map(realAccountSpecPayloadToDomain),
      reconAccountSpecs = ff.reconAccountSpecs.map(reconAccountSpecPayloadToDomain),
      movementSpecs = Seq()
    )

  def realAccountSpecPayloadFromDomain(spec: model.RealAccountSpec): RealAccountSpecPayload =
    RealAccountSpecPayload(name = spec.name, properties = spec.properties)

  def realAccountSpecPayloadToDomain(a: RealAccountSpecPayload): model.RealAccountSpec =
    model.RealAccountSpec(
      name = a.name,
      properties = a.properties
    )

  def reconAccountSpecPayloadFromDomain(spec: model.ReconciliationAccountSpec): ReconAccountSpecPayload =
    ReconAccountSpecPayload(
      name = spec.name,
      matcher = spec.matcher,
      agingLimit = spec.agingLimit,
      properties = spec.properties
    )

  def reconAccountSpecPayloadToDomain(r: ReconAccountSpecPayload): model.ReconciliationAccountSpec =
    model.ReconciliationAccountSpec(
      name = r.name,
      matcher = r.matcher,
      agingLimit = r.agingLimit,
      properties = r.properties
    )

  def fundFlowPayloadToDomain(payload: FundFlowPayload): model.FundFlowConfig =
    model.FundFlowConfig(name = payload.name, fundFlowJson = write(payload))

  def accountPayloadfromDomainModel(dmAccount: model.Account): AccountPayload =
    AccountPayload(
      id = dmAccount.id.toString,
      name = dmAccount.name,
      fundFlowId = dmAccount.fundFlowId,
      accountType = accountTypeFromModel(dmAccount.accountType),
      balance = dmAccount.balance
    )

  def accountTypeFromModel(at: model.AccountType): AccountTypePayload =
    at match
      case Real  => AccountTypePayload.Real
      case Recon => AccountTypePayload.Recon

  def movementPayloadfromDomainModel(dmMovement: model.Movement): MovementPayload =
    MovementPayload(
      id = dmMovement.id.toString,
      referenceId = dmMovement.referenceId.toString,
      outAccountId = dmMovement.outAccountId.toString,
      inAccountId = dmMovement.inAccountId.toString,
      amount = dmMovement.amount,
      properties = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull,
      status = dmMovement.status.toString
    )
