package perfectpair.domain

import com.augustnagro.magnum.DbTx
import perfectpair.adapter.driven.*
import perfectpair.common.model.*
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.{AccountOperations, FundFlowCache}

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
    outAccountId = journalAccount.id,
    dataSourceJson = upickle.write(
      DataSourceConfig(
        parserConfig = CSVParserConfig(
          hasHeader         = false,
          columnDefinitions = Map(0 -> "field1", 1 -> "field2"),
          amountField       = None
        )
      )
    )
  )
  movementSpecRepo.insert(journalToRecon)

  val reportToRecon = MovementSpec(
    name         = "report to recon",
    outAccountId = reconAccount.id,
    inAccountId  = reportAccount.id
  )
  movementSpecRepo.insert(reportToRecon)

  val rule = StringAttributeMatchRule(
    inMovementSpecID  = journalToRecon.id,
    outMovementSpecID = reportToRecon.id,
    inKey             = StringAttribute(key = "id"),
    outKey            = StringAttribute(key = "id")
  )
  val accountOperations = AccountOperations(
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )

  var reconSpec: ReconciliationSpec = ReconciliationSpec(
    accountId = reconAccount.id,
    ruleJson  = upickle.write(rule)
  )
  reconSpecRepo.insert(reconSpec)

}
