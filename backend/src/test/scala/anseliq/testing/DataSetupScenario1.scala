package anseliq.testing

import anseliq.adapter.driving.Payload
import anseliq.common.model.*
import anseliq.common.model.ReconRelationship.{OneToMany, OneToOne}
import anseliq.domain.port.driven.Txn
import com.augustnagro.magnum.transact

import java.util.UUID

class DataSetupScenario1 extends munit.FunSuite:
  private val app = TestAppContainer()

  // The scenario is written in payload terms, as the UI would send it, so each step goes through
  // the same Payload conversions the HTTP routes use on either side of the service call.
  private def createFundFlow(p: FundFlowPayload): Txn[FundFlowPayload] =
    Payload.fundFlowPayloadFromDomain(app.fundFlowService.createFundFlow(Payload.fundFlowPayloadToDomain(p)))
  private def createAccount(p: AccountPayload): Txn[AccountPayload] =
    Payload.accountPayloadFromDomain(app.accountService.createAccount(Payload.accountPayloadToDomain(p)))
  private def createFileFeed(p: FileFeedPayload): Txn[FileFeedPayload] =
    Payload.fileFeedPayloadFromDomain(app.fileFeedService.createFileFeed(Payload.fileFeedPayloadToDomain(p)))
  private def createMovementSpec(p: MovementSpecPayload): Txn[MovementSpecPayload] =
    Payload.movementSpecPayloadFromDomain(
      app.movementSpecService.createMovementSpec(Payload.movementSpecPayloadToDomain(p))
    )
  private def createReconSpec(p: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    Payload.reconSpecPayloadFromDomain(app.reconSpecService.createReconSpec(Payload.reconSpecPayloadToDomain(p)))

  test("Setup data for a complete Fund flow scenario"):
    transact(app.transactor):
      val fundFlowConfig = createFundFlow(
        FundFlowPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val journalAccount =
        createAccount(
          AccountPayload(
            name        = "Journal",
            fundFlowId  = fundFlowConfig.id.get,
            accountType = AccountTypePayload.Secondary
          )
        )

      val reconAccount =
        createAccount(
          AccountPayload(name = "Recon", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = createAccount(
        AccountPayload(name = "Bank", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Primary)
      )

      // tmp_journal.csv: record_date, transaction_id, amount, description (header row present)
      val journalFeed = createFileFeed(
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
        createMovementSpec(
          MovementSpecPayload(
            name         = "Journal to Recon",
            outAccountId = journalAccount.id.get,
            inAccountId  = reconAccount.id.get,
            fileFeedId   = journalFeed.id.get
          )
        )

      val bankFeed = createFileFeed(
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
      val reconToBank = createMovementSpec(
        MovementSpecPayload(
          name         = "Recon to Bank",
          outAccountId = reconAccount.id.get,
          inAccountId  = bankAccount.id.get,
          fileFeedId   = bankFeed.id.get
        )
      )

      val reconSpec = createReconSpec(
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
      val fundFlowConfig = createFundFlow(
        FundFlowPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val visa =
        createAccount(
          AccountPayload(name = "Visa", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Secondary)
        )

      val reconAccount =
        createAccount(
          AccountPayload(name = "BAI Recon", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = createAccount(
        AccountPayload(name = "Bank", fundFlowId = fundFlowConfig.id.get, accountType = AccountTypePayload.Primary)
      )

      // A single BAI2 feed covers visa's account activity; reconToBank stays on its own CSV feed.
      val baiFeed = createFileFeed(
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
        createMovementSpec(
          MovementSpecPayload(
            name          = "Journal to Recon",
            outAccountId  = visa.id.get,
            inAccountId   = reconAccount.id.get,
            fileFeedId    = baiFeed.id.get,
            routingFilter = Some(RoutingFilter(propertyField = "account_number", matchValue = "1111111"))
          )
        )

      val bankFeed = createFileFeed(
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
      val reconToBank = createMovementSpec(
        MovementSpecPayload(
          name         = "Recon to Bank",
          outAccountId = reconAccount.id.get,
          inAccountId  = bankAccount.id.get,
          fileFeedId   = bankFeed.id.get
        )
      )

      val reconSpec = createReconSpec(
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
