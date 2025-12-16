package com.eweise.perfectpair.adapter.driving

import com.eweise.perfectpair.common.model.AdapterModel.{AccountPayload, MovementPayload}
import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model as dm
import upickle.*
import upickle.default.*

object Payload:
    def accountPayloadfromDomainModel(dmAccount: model.Account): AccountPayload =
      AccountPayload(
        id = dmAccount.id.toString,
        name = dmAccount.name,
        fundFlowId = dmAccount.fundFlowId,
        accountType = dmAccount.accountType.toString,
        balance = dmAccount.balance
      )

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
