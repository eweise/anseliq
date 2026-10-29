package anseliq.domain

import anseliq.adapter.config.AppContainer
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import com.augustnagro.magnum.DbTx

import java.util.UUID

// Inserts a fresh ledger (accounts, feeds, movement specs, recon spec) through the given app's
// repos. Those repos and the app's bookkeeper are re-exported, so tests can keep writing
// `data.movementRepo` / `data.bookkeeper` and get the same instances, and clock, as the rest of `app`.
class DataSetup(val app: AppContainer)(using dbTx: DbTx) {
  export app.{
    accountRepo,
    exceptionCommentRepo,
    exceptionRepo,
    fileFeedRepo,
    ledgerRepo,
    bookkeeper,
    movementRepo,
    movementSpecRepo,
    reconRepo,
    reconSpecRepo
  }

  dbTx.connection.getMetaData
  val reconAccountName   = "recon"
  val unreconAccountName = "unrecon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"
  val bankReportName     = "bank_report"
  val postJournalName    = "post_journal"
  val ledger = Ledger(
    name = UUID.randomUUID().toString
  )
  val props = Map("key1" -> "value1", "key2" -> "value2")
  val reconAccount = Account(
    name        = reconAccountName,
    ledgerId  = ledger.id,
    accountType = AccountType.Recon,
    properties  = JSONBProps(props)
  )
  val journalAccount = Account(
    name        = journalAccountName,
    ledgerId  = ledger.id,
    accountType = AccountType.Primary,
    properties  = JSONBProps(props)
  )
  val reportAccount = Account(
    name        = reportAccountName,
    ledgerId  = ledger.id,
    accountType = AccountType.Secondary,
    properties  = JSONBProps(props)
  )
  val journalToReconFeed = FileFeed(
    dataSourceJson = upickle.write(
      DataSourceConfig(
        parserConfig = CsvParserConfig(
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

  ledgerRepo.insert(ledger)
  var reconSpec: ReconciliationSpec = ReconciliationSpec(
    accountId         = reconAccount.id,
    ruleJson          = upickle.write(rule),
    inMovementSpecId  = journalToRecon.id,
    outMovementSpecId = reportToRecon.id
  )
  accountRepo.insert(reconAccount)
  accountRepo.insert(journalAccount)
  accountRepo.insert(reportAccount)
  fileFeedRepo.insert(journalToReconFeed)
  fileFeedRepo.insert(reportToReconFeed)
  movementSpecRepo.insert(journalToRecon)
  movementSpecRepo.insert(reportToRecon)
  reconSpecRepo.insert(reconSpec)
}
