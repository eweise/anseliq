package anseliq.testing

import com.augustnagro.magnum.transact
import anseliq.common.model.*
import anseliq.common.model.ReconRelationship.{OneToMany, OneToOne}

import java.util.UUID

class DataSetupScenario1 extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Setup data for a complete Fund flow scenario"):
    transact(app.transactor):
      val fundFlowConfig = app.fundFlowOperations.createFundFlow(
        FundFlowPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val journalAccount =
        app.fundFlowOperations.createAccount(
          AccountPayload(name = "Journal", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Secondary)
        )

      val reconAccount =
        app.fundFlowOperations.createAccount(
          AccountPayload(name = "Recon", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = app.fundFlowOperations.createAccount(
        AccountPayload(name = "Bank", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Primary)
      )

      // tmp_journal.csv: record_date, transaction_id, amount, description (header row present)
      val journalFeed = app.fundFlowOperations.createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "Journal Feed",
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
      val journalToRecon =
        app.fundFlowOperations.createMovementSpec(
          MovementSpecPayload(
            name         = "Journal to Recon",
            outAccountId = journalAccount.id.get,
            inAccountId  = reconAccount.id.get,
            fileFeedId   = journalFeed.id.get
          )
        )

      val bankFeed = app.fundFlowOperations.createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "Bank Feed",
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
      val reconToBank = app.fundFlowOperations.createMovementSpec(
        MovementSpecPayload(
          name         = "Recon to Bank",
          outAccountId = reconAccount.id.get,
          inAccountId  = bankAccount.id.get,
          fileFeedId   = bankFeed.id.get
        )
      )

      val reconSpec = app.fundFlowOperations.createReconSpec(
        ReconciliationSpecPayload(
          accountId         = reconAccount.id,
          inMovementSpecId  = journalToRecon.id.get,
          outMovementSpecId = reconToBank.id.get,
          relationship      = OneToOne,
          rule = StringAttributeMatchRule(
            inKey  = StringAttribute(key = "transaction_id"),
            outKey = StringAttribute(key = "txn_id")
          )
        )
      )

  test("Setup For many to one with multiple accounts using BAI"):
    transact(app.transactor):
      val fundFlowConfig = app.fundFlowOperations.createFundFlow(
        FundFlowPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val visa =
        app.fundFlowOperations.createAccount(
          AccountPayload(name = "Visa", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Secondary)
        )

      val reconAccount =
        app.fundFlowOperations.createAccount(
          AccountPayload(name = "BAI Recon", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = app.fundFlowOperations.createAccount(
        AccountPayload(name = "Bank", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Primary)
      )

      // A single BAI2 feed covers visa's account activity; reconToBank stays on its own CSV feed.
      val baiFeed = app.fundFlowOperations.createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "BAI Feed",
          dataSourceConfig = Some(
            DataSourceConfig(
              parserConfig     = BAIParserConfig(),
              connectionConfig = S3ConnectionConfig(s3Bucket = "onparity", filePath = "bai")
            )
          )
        )
      )
      val journalToRecon =
        app.fundFlowOperations.createMovementSpec(
          MovementSpecPayload(
            name          = "Journal to Recon",
            outAccountId  = visa.id.get,
            inAccountId   = reconAccount.id.get,
            fileFeedId    = baiFeed.id.get,
            routingFilter = Some(RoutingFilter(propertyField = "account_number", matchValue = "1111111"))
          )
        )

      val bankFeed = app.fundFlowOperations.createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "Bank Feed",
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
      val reconToBank = app.fundFlowOperations.createMovementSpec(
        MovementSpecPayload(
          name         = "Recon to Bank",
          outAccountId = reconAccount.id.get,
          inAccountId  = bankAccount.id.get,
          fileFeedId   = bankFeed.id.get
        )
      )

      val reconSpec = app.fundFlowOperations.createReconSpec(
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
