package com.eweise.perfectpair.domain.service

import com.eweise.perfectpair.common.model.model.{Money, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, Txn}

class AccountOperations(
  val fundFlowCache: FundFlowCache,
  val accountRepo:   AccountRepo,
  val movementRepo:  MovementRepo,
  val configRepo:    FundFlowConfigRepo
):

  def createConfig(fundFlowConfig: FundFlowConfig): Txn[FundFlowConfig] =
    configRepo.insert(fundFlowConfig)
    fundFlowConfig

  def postAmount(
    fundFlowName: String,
    movementName: String,
    amount:       Money,
    properties:   Properties = Map[String, String]()
  ): Txn[Unit] =
    // find the fund flow
    val fundFlowConfig = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw NotFoundException(s"no fund flow found for $fundFlowName")


  def findMismatches(fundFlowName: String): Txn[Vector[Account]] =
    val fundflow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw new NotFoundException(s"getMismatches; fundFlowName: $fundFlowName does not exist")
    accountRepo.findNominalWithNonZeroBalance(fundflow.id)

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
          properties = JSONBProps(Map()), // todo
        )
        accountRepo.insert(newAccount)
        newAccount
