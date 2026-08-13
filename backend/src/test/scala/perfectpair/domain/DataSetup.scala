package perfectpair.domain

import com.augustnagro.magnum.DbTx
import perfectpair.adapter.driven.*
import perfectpair.common.model.*
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.{FundFlowCache, FundFlowOperations, Ledger}

import java.util.UUID

class DataSetup(using dbTx: DbTx) {
  dbTx.connection.getMetaData
  val reconAccountName   = "recon"
  val unreconAccountName = "unrecon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"
  val bankReportName     = "bank_report"
  val postJournalName = "post_journal"
  val fundFlowConfig = FundFlowConfig(
    name = UUID.randomUUID().toString
  )
  val props = Map("key1" -> "value1", "key2" -> "value2")
  val reconAccount = Account(
    name       = reconAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  val journalAccount = Account(
    name       = journalAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  val reportAccount = Account(
    name       = reportAccountName,
    fundFlowId = fundFlowConfig.id,
    properties = JSONBProps(props)
  )
  val journalToRecon = MovementSpec(
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
  val reportToRecon = MovementSpec(
    outAccountId = reconAccount.id,
    inAccountId  = reportAccount.id
  )
  val rule = StringAttributeMatchRule(
    inKey  = StringAttribute(key = "id"),
    outKey = StringAttribute(key = "id")
  )

  fundFlowConfigRepo.insert(fundFlowConfig)
  val fundFlowOperations = FundFlowOperations()
  var reconSpec: ReconciliationSpec = ReconciliationSpec(
    accountId         = reconAccount.id,
    ruleJson          = upickle.write(rule),
    inMovementSpecId  = journalToRecon.id,
    outMovementSpecId = reportToRecon.id
  )
  accountRepo.insert(reconAccount)

  given movementRepo:       MovementRepo           = MovementRepoLive()
  accountRepo.insert(journalAccount)

  given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  accountRepo.insert(reportAccount)

  given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  movementSpecRepo.insert(journalToRecon)

  given reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()

  given disputeRepo:        DisputeRepo            = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo     = DisputeCommentRepoLive()
  movementSpecRepo.insert(reportToRecon)

  given accountRepo:        AccountRepo            = AccountRepoLive()

  given ledger:             Ledger                 = Ledger()

  given fundFlowCache:      FundFlowCache          = FundFlowCache(fundFlowConfigRepo)

  given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  reconSpecRepo.insert(reconSpec)

}
