package perfectpair.testing

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.{CSVParserConfig, DataSourceConfig}
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.{ClockService, ClockServiceLive, EventStore, Ingestor}

import java.util.UUID
import javax.sql.DataSource

class DataSetupScenario1 extends munit.FunSuite:
  given clockService: ClockService = ClockServiceLive()
  given dataSource:   DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given transactor:   Transactor   = Transactor(dataSource)

  given eventRepo:          EventRepo          = EventRepoLive()
  given eventStore:         EventStore         = EventStore()
  given accountRepo:        AccountRepo        = AccountRepoLive()
  given movementSpecRepo:   MovementSpecRepo   = MovementSpecRepoLive()
  given movementRepo:       MovementRepo       = MovementRepoLive()
  given fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()
  given fileInventoryRepo:  FileInventoryRepo  = FileInventoryRepoLive()
  given fileSourceFactory:  FileSourceFactory  = S3FileSourceFactory()

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
              hasHeader = true,
              columnDefinitions =
                Map(0 -> "record_date", 1 -> "transaction_id", 2 -> "amount", 3 -> "description"),
              amountField = Some(2)
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
      ingestor.ingest(journalToRecon, getClass.getResourceAsStream("/tmp_journal.csv"))
      ingestor.ingest(reconToBank, getClass.getResourceAsStream("/tmp_bank.csv"))

      val movements = movementRepo.findAll
      assertEquals(movements.count(_.movementSpecId == journalToRecon.id), 4)
      assertEquals(movements.count(_.movementSpecId == reconToBank.id), 3)
