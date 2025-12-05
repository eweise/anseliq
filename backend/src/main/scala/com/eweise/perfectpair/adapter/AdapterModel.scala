package com.eweise.perfectpair.adapter

import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model as dm
import model.{ID, Money, Properties}
import upickle.*
import upickle.default.*

object AdapterModel:
  case class Account(
    id: String,
    name: String,
    fundFlowId: ID,
    accountType: String,
    balance: Money
  ) derives ReadWriter

  object Account:
    def fromDomainModel(dmAccount: model.Account): AdapterModel.Account =
      AdapterModel.Account(
        id = dmAccount.id.toString,
        name = dmAccount.name,
        fundFlowId = dmAccount.fundFlowId,
        accountType = dmAccount.accountType.toString,
        balance = dmAccount.balance
      )

  case class Movement(
    id: String,
    referenceId: String,
    outAccountId: String,
    inAccountId: String,
    amount: Money,
    properties: Properties,
    reconciliationId: String,
    status: String
  ) derives ReadWriter

  object Movement:
    def fromDomainModel(dmMovement: model.Movement): AdapterModel.Movement =
      AdapterModel.Movement(
        id = dmMovement.id.toString,
        referenceId = dmMovement.referenceId.toString,
        outAccountId = dmMovement.outAccountId.toString,
        inAccountId = dmMovement.inAccountId.toString,
        amount = dmMovement.amount,
        properties = dmMovement.properties.props,
        reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull,
        status = dmMovement.status.toString
      )
