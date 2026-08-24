package onparity.testing

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.*
import onparity.common.model.ReconRelationship.OneToMany
import onparity.domain.port.driven.*
import onparity.domain.service.*

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
  given operations:         FundFlowOperations     = FundFlowOperations()

  test("Setup data for a complete Fund flow scenario"):
    transact(transactor):
      val fundFlowConfig = operations.createFundFlow(
        FundFlowPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val journalAccount =
        operations.createAccount(AccountPayload(name = "Journal", fundFlowId = fundFlowConfig.id.get))

      val reconAccount =
        operations.createAccount(AccountPayload(name = "Recon", fundFlowId = fundFlowConfig.id.get))

      val bankAccount = operations.createAccount(AccountPayload(name = "Bank", fundFlowId = fundFlowConfig.id.get))

      // tmp_journal.csv: record_date, transaction_id, amount, description (header row present)
      val journalToRecon =
        operations.createMovementSpec(
          MovementSpecPayload(
            expectedCadenceHours = Some(24),
            name                 = "Journal to Recon",
            outAccountId         = journalAccount.id.get,
            inAccountId          = reconAccount.id.get,
            dataSourceConfig = Some(
              DataSourceConfig(
                parserConfig = CSVParserConfig(
                  hasHeader         = false,
                  columnDefinitions = Map(0 -> "transaction_id", 1 -> "amount", 2 -> "record_date"),
                  amountField       = Some(1)
                ),
                connectionConfig = S3ConnectionConfig(
                  s3Bucket = "onparity",
                  filePath = "internal"
                )
              )
            )
          )
        )

      val reconToBank = operations.createMovementSpec(
        MovementSpecPayload(
          expectedCadenceHours = Some(24),
          name                 = "Recon to Bank",
          outAccountId         = reconAccount.id.get,
          inAccountId          = bankAccount.id.get,
          dataSourceConfig = Some(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader         = false,
                columnDefinitions = Map(0 -> "txn_id", 1 -> "amount"),
                amountField       = Some(1)
              ),
              connectionConfig = S3ConnectionConfig(
                s3Bucket = "onparity",
                filePath = "bank"
              )
            )
          )
        )
      )

      val reconSpec = operations.createReconSpec(
        ReconciliationSpecPayload(
          accountId         = reconAccount.id,
          inMovementSpecId  = journalToRecon.id.get,
          outMovementSpecId = reconToBank.id.get,
          relationship      = OneToMany,
          rule = StringAttributeMatchRule(
            inKey  = StringAttribute(key = "transaction_id"),
            outKey = StringAttribute(key = "txn_id")
          )
        )
      )
