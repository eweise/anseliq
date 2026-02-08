package com.eweise.perfectpair.domain

import com.augustnagro.magnum.DbTx
import com.eweise.perfectpair.adapter.driven.{
  AccountRepoLive,
  FundFlowConfigRepoLive,
  MovementRepoLive,
  MovementSpecRepoLive,
  ReconciliationRepoLive,
  ReconciliationSpecRepoLive
}
import com.eweise.perfectpair.common.model.EqualityMatcher
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.port.driven.{
  AccountRepo,
  FundFlowConfigRepo,
  MovementRepo,
  MovementSpecRepo,
  ReconciliationRepo,
  ReconciliationSpecRepo
}
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache}

import java.util.UUID

class DataSetup(using DbTx) {

  val fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  val movementRepo:       MovementRepo           = MovementRepoLive()
  val movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  val reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  val reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  val accountRepo:        AccountRepo            = AccountRepoLive()
  val fundFlowCache:      FundFlowCache          = FundFlowCache(fundFlowConfigRepo)

  val reconAccountName   = "recon"
  val unreconAccountName = "unrecon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"
  val bankReportName     = "bank_report"

  val postJournalName = "post_journal"

  val fundFlowConfig = FundFlowConfig(
    name = UUID.randomUUID().toString
  )

  fundFlowConfigRepo.insert(fundFlowConfig)

  val props = Map("key1" -> "value1", "key2" -> "value2")
  val reconAccount = Account(
    name       = reconAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  accountRepo.insert(reconAccount)

  val journalAccount = Account(
    name       = journalAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  accountRepo.insert(journalAccount)

  val reportAccount = Account(
    name       = reportAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  accountRepo.insert(reportAccount)

  val journalToRecon = MovementSpec(
    name         = "journal to recon",
    inAccountId  = reconAccount.id,
    outAccountId = journalAccount.id
  )
  movementSpecRepo.insert(journalToRecon)

  val reportToRecon = MovementSpec(
    name         = "report to recon",
    outAccountId = reconAccount.id,
    inAccountId  = reportAccount.id
  )
  movementSpecRepo.insert(reportToRecon)

  val equalityMatcher = EqualityMatcher(
    inMovementSpecID  = journalToRecon.id,
    outMovementSpecID = reportToRecon.id,
    inKey            = "id",
    outKey           = "id"
  )
  var reconSpec: ReconciliationSpec = ReconciliationSpec(
    fundFlowId        = fundFlowConfig.id,
    matchingRulesJson = upickle.write(equalityMatcher)
  )

  reconSpecRepo.insert(reconSpec)

  val accountOperations = AccountOperations(
    fundFlowCache      = fundFlowCache,
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )

}
