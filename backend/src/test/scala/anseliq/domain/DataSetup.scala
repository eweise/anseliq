package anseliq.domain

import com.augustnagro.magnum.DbTx
import anseliq.adapter.driven.*
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.*
import anseliq.domain.service.{ClockService, ClockServiceLive, FundFlowCache, FundFlowOperations, Ledger}

import java.util.UUID

// clockService: given-resolution is tried first (so a caller's own ClockService given, if any, is
// what's actually used), falling back to the default only when the caller has none — a plain
// class-body `given` here would instead re-export via `import data.given`, competing with (and
// sometimes losing an ambiguity error to) a caller's own ClockService given of the same type.
class DataSetup(using dbTx: DbTx, clockService: ClockService = ClockServiceLive()) {
  dbTx.connection.getMetaData
  val reconAccountName   = "recon"
  val unreconAccountName = "unrecon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"
  val bankReportName     = "bank_report"
  val postJournalName    = "post_journal"
  val fundFlowConfig = FundFlowConfig(
    name = UUID.randomUUID().toString
  )
  val props = Map("key1" -> "value1", "key2" -> "value2")
  val reconAccount = Account(
    name        = reconAccountName,
    fundFlowId  = fundFlowConfig.id,
    accountType = AccountType.Recon,
    properties  = JSONBProps(props)
  )
  val journalAccount = Account(
    name        = journalAccountName,
    fundFlowId  = fundFlowConfig.id,
    accountType = AccountType.Secondary,
    properties  = JSONBProps(props)
  )
  val reportAccount = Account(
    name        = reportAccountName,
    fundFlowId  = fundFlowConfig.id,
    accountType = AccountType.Primary,
    properties  = JSONBProps(props)
  )
  val journalToReconFeed = FileFeed(
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
  val reportToReconFeed = FileFeed()
  val journalToRecon = MovementSpec(
    inAccountId  = reconAccount.id,
    outAccountId = journalAccount.id,
    fileFeedId   = journalToReconFeed.id
  )
  val reportToRecon = MovementSpec(
    outAccountId = reconAccount.id,
    inAccountId  = reportAccount.id,
    fileFeedId   = reportToReconFeed.id
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

  given movementRepo: MovementRepo = MovementRepoLive()
  accountRepo.insert(journalAccount)

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()
  accountRepo.insert(reportAccount)

  given fileFeedRepo: FileFeedRepo = FileFeedRepoLive()
  fileFeedRepo.insert(journalToReconFeed)
  fileFeedRepo.insert(reportToReconFeed)

  given reconSpecRepo: ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  movementSpecRepo.insert(journalToRecon)

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given disputeRepo: DisputeRepo = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()
  movementSpecRepo.insert(reportToRecon)

  given accountRepo: AccountRepo = AccountRepoLive()

  given ledger: Ledger = Ledger()

  given fundFlowCache: FundFlowCache = FundFlowCache(fundFlowConfigRepo)

  given fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()
  reconSpecRepo.insert(reconSpec)

}
