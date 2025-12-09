package com.eweise.perfectpair.domain.service

import com.eweise.perfectpair.common.model.model.{Money, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, Txn}

class AccountOperations(
  val fundFlowCache: FundFlowCache,
  val accountRepo: AccountRepo,
  val movementRepo: MovementRepo,
  val configRepo: FundFlowConfigRepo
):

  def createConfig(fundFlowConfig: FundFlowConfig): Txn[FundFlowConfig] =
    configRepo.insert(fundFlowConfig)
    fundFlowConfig

  def postAmount(
    fundFlowName: String,
    movementName: String,
    amount: Money,
    properties: Properties = Map[String, String]()
  ): Txn[Unit] =
    // find the fund flow
    val fundFlowConfig = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw NotFoundException(s"no fund flow found for $fundFlowName")

    val fundFlow = fundFlowConfig.fundFlow
    val movementSpec = fundFlow.movementSpecs
      .find(_.name == movementName)
      .getOrElse(
        throw NotFoundException(s"no movement found for fundFlowConfig $movementName")
      )
    val inAccountSpec = fundFlow.accountSpecs
      .find(_.name == movementSpec.inAccountName)
      .getOrElse(
        throw NotFoundException(s"no account found for account name ${movementSpec.inAccountName}")
      )
    val inAccountType = if inAccountSpec.isReconSpec then model.AccountType.Recon else model.AccountType.Real
    val inAccount     = upsertAccount(movementSpec.inAccountName, amount, inAccountType, fundFlowConfig)

    val outAccountSpec = fundFlow.accountSpecs
      .find(_.name == movementSpec.outAccountName)
      .getOrElse(
        throw NotFoundException(s"no account found for account name ${movementSpec.outAccountName}")
      )
    val outAccountType = if outAccountSpec.isReconSpec then model.AccountType.Recon else model.AccountType.Real
    val outAccount     = upsertAccount(movementSpec.outAccountName, amount * -1, outAccountType, fundFlowConfig)

    movementRepo.insert:
      Movement(
        properties = JSONBProps(properties),
        outAccountId = outAccount.id,
        inAccountId = inAccount.id,
        amount = amount
      )

  def findMismatches(fundFlowName: String): Txn[Vector[Account]] =
    val fundflow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw new NotFoundException(s"getMismatches; fundFlowName: $fundFlowName does not exist")
    accountRepo.findNominalWithNonZeroBalance(fundflow.id)

  private def upsertAccount(
    accountName: String,
    amount: Money,
    accountType: AccountType,
    ffConfig: FundFlowConfig
  ): Txn[Account] =
    accountRepo.findByFundFlowAndAccountName(ffConfig.id, accountName) match

      case Some(found) =>
        accountRepo.update(found.copy(balance = found.balance + amount))
        found

      case _ =>
        val newAccount = Account(
          name = accountName,
          accountType = accountType,
          fundFlowId = ffConfig.id,
          balance = amount
        )
        accountRepo.insert(newAccount)
        newAccount
