package com.eweise.perfectpair.adapter.driving

import com.eweise.perfectpair.common.model.{AccountPayload, AccountTypePayload, FundFlowPayload, MovementPayload}
import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model as dm
import com.eweise.perfectpair.domain.model.AccountType.{Real, Recon}
import upickle.*
import upickle.default.*

import java.time.OffsetDateTime

object Payload:

  def fundFlowPayloadFromDomain(ff: model.FundFlowConfig, a: Seq[model.Account]): FundFlowPayload =
    FundFlowPayload(
      id = ff.id.toString,
      name = ff.name,
      accounts = a.map(accountPayloadfromDomainModel),
      createdAt = ff.createdAt.getOrElse(OffsetDateTime.now()),
      updatedAt = ff.updatedAt.getOrElse(OffsetDateTime.now())
    )

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
