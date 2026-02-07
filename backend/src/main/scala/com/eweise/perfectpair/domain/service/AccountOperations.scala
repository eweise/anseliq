package com.eweise.perfectpair.domain.service

import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, MovementSpecRepo, Txn}

class AccountOperations(
  val fundFlowCache:    FundFlowCache,
  val accountRepo:      AccountRepo,
  val movementRepo:     MovementRepo,
  val movementSpecRepo: MovementSpecRepo,
  val configRepo:       FundFlowConfigRepo
):

  def postAmount(
    movementSpec: MovementSpec,
    amount:       Money,
    referenceID:  ID,
    properties:   Properties = Map[String, String]()
  ): Txn[Unit] =
    // todo do I need to find the accounts if the movement
    // spec already has the ids? I think no
    val inAccount = accountRepo
      .findById(movementSpec.inAccountId)
      .getOrElse:
        throw NotFoundException(s"no account found for ${movementSpec.inAccountId}")

    val outAccount = accountRepo
      .findById(movementSpec.outAccountId)
      .getOrElse:
        throw NotFoundException(s"no account found for ${movementSpec.outAccountId}")

    val movement = Movement(
      referenceId  = referenceID,
      amount       = amount,
      properties   = JSONBProps(properties),
      inAccountId  = inAccount.id,
      outAccountId = outAccount.id
    )
    movementRepo.insert(movement)

  private def upsertAccount(
    accountName: String,
    amount:      Money,
    ffConfig:    FundFlowConfig
  ): Txn[Account] =
    accountRepo.findByFundFlowAndAccountName(ffConfig.id, accountName) match

      case Some(found) =>
        accountRepo.update(found.copy(balance = found.balance + amount))
        found

      case _ =>
        val newAccount = Account(
          name       = accountName,
          fundFlowId = ffConfig.id,
          balance    = amount,
          properties = JSONBProps(Map()) // todo
        )
        accountRepo.insert(newAccount)
        newAccount
