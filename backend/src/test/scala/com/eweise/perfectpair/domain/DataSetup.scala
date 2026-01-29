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

  val reconAccountSpec = ReconciliationAccountSpec(
    name = reconAccountName,
    matcher = EqualityMatcher(inKeys = Array("id"), outKeys = Array("id"))
  )

  var journalAccountSpec = RealAccountSpec(name = journalAccountName)
  var reportAccountSpec  = RealAccountSpec(name = reportAccountName)

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

  var fundFlow =
    FundFlowSpec(
      realAccountSpecs = Seq(journalAccountSpec, reportAccountSpec),
      reconAccountSpecs = Seq(reconAccountSpec),
      movementSpecs = Seq(movementSpecPostJournal, movementSpecBankReport)
    )

  var fundFlowConfig = FundFlowConfig(
    name = UUID.randomUUID().toString,
    fundFlowJson = write(fundFlow)
  )

  fundFlowConfigRepo.insert(fundFlowConfig)

  val reconAccount = Account(
    name = reconAccountSpec.name,
    accountType = AccountType.Recon,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(reconAccount)

  val journalAccount = Account(
    name = journalAccountSpec.name,
    accountType = AccountType.Real,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(journalAccount)

  val reportAccount = Account(
    name = reportAccountSpec.name,
    accountType = AccountType.Real,
    fundFlowId = fundFlowConfig.id
  )
  accountRepo.insert(reportAccount)
}
