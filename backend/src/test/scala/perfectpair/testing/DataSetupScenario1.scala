package perfectpair.testing

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.{CSVParserConfig, DataSourceConfig, StringAttribute, StringAttributeMatchRule}
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.*

import java.util.UUID
import javax.sql.DataSource

class DataSetupScenario1 extends munit.FunSuite:
  given clockService: ClockService = ClockServiceLive()

  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo:          EventRepo              = EventRepoLive()
  given eventStore:         EventStore             = EventStore()
  given accountRepo:        AccountRepo            = AccountRepoLive()
  given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  given movementRepo:       MovementRepo           = MovementRepoLive()
  given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given fileInventoryRepo:  FileInventoryRepo      = FileInventoryRepoLive()
  given fileSourceFactory:  FileSourceFactory      = S3FileSourceFactory()
  given ledger:             Ledger                 = Ledger()
  given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  test("Setup data for a complete Fund flow scenario"):
    transact(transactor):
      val fundFlowConfig = FundFlowConfig(name = s"Scenario1-${UUID.randomUUID()}")
      fundFlowConfigRepo.insert(fundFlowConfig)

      val journalAccount = Account(name = "Journal", fundFlowId = fundFlowConfig.id)
      val reconAccount   = Account(name = "Recon", fundFlowId = fundFlowConfig.id, isRecon = true)
      val bankAccount    = Account(name = "Bank", fundFlowId = fundFlowConfig.id)
      accountRepo.insert(journalAccount)
      accountRepo.insert(reconAccount)
      accountRepo.insert(bankAccount)

      // tmp_journal.csv: record_date, transaction_id, amount, description (header row present)
      val journalToRecon = MovementSpec(
        name         = "Journal to Recon",
        outAccountId = journalAccount.id,
        inAccountId  = reconAccount.id,
        dataSourceJson = upickle.write(
          DataSourceConfig(
            parserConfig = CSVParserConfig(
              hasHeader         = true,
              columnDefinitions = Map(0 -> "record_date", 1 -> "transaction_id", 2 -> "amount", 3 -> "description"),
              amountField       = Some(2)
            )
          )
        )
      )
      // tmp_bank.csv: record_date, transaction_id, amount (no header row)
      val reconToBank = MovementSpec(
        name         = "Recon to Bank",
        outAccountId = reconAccount.id,
        inAccountId  = bankAccount.id,
        dataSourceJson = upickle.write(
          DataSourceConfig(
            parserConfig = CSVParserConfig(
              hasHeader         = false,
              columnDefinitions = Map(0 -> "record_date", 1 -> "transaction_id", 2 -> "amount"),
              amountField       = Some(2)
            )
          )
        )
      )
      movementSpecRepo.insert(journalToRecon)
      movementSpecRepo.insert(reconToBank)

      val ingestor = Ingestor()
      ingestor.ingest(
        journalToRecon,
        InMemoryFileSourceEntry("test1"),
        getClass.getResourceAsStream("/tmp_journal.csv")
      )
      ingestor.ingest(reconToBank, InMemoryFileSourceEntry("test2"), getClass.getResourceAsStream("/tmp_bank.csv"))

      val movements = movementRepo.findAll
      assertEquals(movements.count(_.movementSpecId == journalToRecon.id), 4)
      assertEquals(movements.count(_.movementSpecId == reconToBank.id), 3)

      val reconSpec: ReconciliationSpec = ReconciliationSpec(
        accountId         = reconAccount.id,
        inMovementSpecId  = journalToRecon.id,
        outMovementSpecId = reconToBank.id,
        ruleJson = upickle.write(
          StringAttributeMatchRule(
            inKey  = StringAttribute(key = "transaction_id"),
            outKey = StringAttribute(key = "txn_id")
          )
        )
      )
      reconSpecRepo.insert(reconSpec)
