package com.eweise.perfectpair.domain

import com.augustnagro.magnum.DbTx
import com.eweise.perfectpair.adapter.driven.{
  AccountRepoLive,
  FundFlowConfigRepoLive,
  MovementRepoLive,
  ReconciliationRepoLive,
  ReconciliationSpecRepoLive
}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, ReconciliationRepo, ReconciliationSpecRepo}
import com.eweise.perfectpair.domain.service.FundFlowCache

import java.util.UUID

class DataSetup(using DbTx) {

  val fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  val movementRepo:       MovementRepo           = MovementRepoLive()
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

  var reconSpec: ReconciliationSpec = ReconciliationSpec(fundFlowId = fundFlowConfig.id)

  reconSpecRepo.insert(reconSpec)

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

}
