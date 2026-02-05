package com.eweise.perfectpair.domain

import com.augustnagro.magnum.DbTx
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, FundFlowConfigRepoLive, MovementRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.common.model.EqualityMatcher
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, ReconciliationRepo}
import com.eweise.perfectpair.domain.service.FundFlowCache
import upickle.default.*

import java.util.UUID

class DataSetup(using DbTx) {

  val fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()
  val movementRepo: MovementRepo             = MovementRepoLive()
  val reconRepo: ReconciliationRepo          = ReconciliationRepoLive()
  val accountRepo: AccountRepo               = AccountRepoLive()
  val fundFlowCache: FundFlowCache           = FundFlowCache(fundFlowConfigRepo)

  val reconAccountName   = "recon"
  val unreconAccountName = "unrecon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"

  val postJournalName = "post_journal"
  var movementSpecPostJournal = MovementSpec(
    name = postJournalName,
    inAccountName = journalAccountName,
    outAccountName = reconAccountName
  )

  val bankReportName = "bank_report"
  var movementSpecBankReport = MovementSpec(
    name = bankReportName,
    inAccountName = reconAccountName,
    outAccountName = reportAccountName
  )

  var fundFlowConfig = FundFlowConfig(
    name = UUID.randomUUID().toString,
  )

  fundFlowConfigRepo.insert(fundFlowConfig)

  val reconAccount = Account(
    name = reconAccountName,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(reconAccount)

  val journalAccount = Account(
    name = journalAccountName,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(journalAccount)

  val reportAccount = Account(
    name = reportAccountName,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(reportAccount)
}
