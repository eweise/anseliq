package anseliq.testing

import anseliq.adapter.driving.PayloadMapper
import anseliq.common.model.*
import anseliq.common.model.ReconRelationship.{OneToMany, OneToOne}
import anseliq.domain.port.driven.Txn
import com.augustnagro.magnum.transact

import java.util.UUID

class DataSetupScenario1 extends munit.FunSuite:
  private val app = TestAppContainer()

  // The scenario is written in payload terms, as the UI would send it, so each step goes through
  // the same Payload conversions the HTTP routes use on either side of the service call.
  private def createLedger(p: LedgerPayload): Txn[LedgerPayload] =
    PayloadMapper.ledgerPayloadFromDomain(app.ledgerService.createLedger(PayloadMapper.ledgerPayloadToDomain(p)))
  private def createAccount(p: AccountPayload): Txn[AccountPayload] =
    PayloadMapper.accountPayloadFromDomain(app.accountService.createAccount(PayloadMapper.accountPayloadToDomain(p)))
  private def createFileFeed(p: FileFeedPayload): Txn[FileFeedPayload] =
    PayloadMapper.fileFeedPayloadFromDomain(
      app.fileFeedService.createFileFeed(PayloadMapper.fileFeedPayloadToDomain(p))
    )
  private def createMovementSpec(p: MovementSpecPayload): Txn[MovementSpecPayload] =
    PayloadMapper.movementSpecPayloadFromDomain(
      app.movementSpecService.createMovementSpec(PayloadMapper.movementSpecPayloadToDomain(p))
    )
  private def createReconSpec(p: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    PayloadMapper.reconSpecPayloadFromDomain(
      app.reconSpecService.createReconSpec(PayloadMapper.reconSpecPayloadToDomain(p))
    )

  test("Setup data for a complete Ledger scenario"):
    transact(app.transactor):
      val ledger = createLedger(
        LedgerPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val journalAccount =
        createAccount(
          AccountPayload(
            name        = "Journal",
            ledgerId    = ledger.id.get,
            accountType = AccountTypePayload.Primary
          )
        )

      val reconAccount =
        createAccount(
          AccountPayload(name = "Recon", ledgerId = ledger.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = createAccount(
        AccountPayload(name = "Bank", ledgerId = ledger.id.get, accountType = AccountTypePayload.Secondary)
      )

      // tmp_journal.csv: record_date, transaction_id, amount, description (header row present)
      val journalFeed = createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "Journal Feed",
          dataSourceConfig = Some(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
                hasHeader         = false,
                columnDefinitions = Map(0 -> "transaction_id", 1 -> "amount", 2 -> "record_date"),
                amountField       = Some(1)
              ),
              connectionConfig = S3ConnectionConfig(
                s3Bucket = "scenario1",
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
              parserConfig = CsvParserConfig(
                hasHeader         = false,
                columnDefinitions = Map(0 -> "txn_id", 1 -> "amount"),
                amountField       = Some(1)
              ),
              connectionConfig = S3ConnectionConfig(
                s3Bucket = "scenario1",
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

      createReconSpec(
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
      val ledger = createLedger(
        LedgerPayload(
          name             = s"Scenario1-${UUID.randomUUID()}",
          dailyCutoffHours = 0
        )
      )

      val visa =
        createAccount(
          AccountPayload(name = "Visa", ledgerId = ledger.id.get, accountType = AccountTypePayload.Primary)
        )

      val reconAccount =
        createAccount(
          AccountPayload(name = "BAI Recon", ledgerId = ledger.id.get, accountType = AccountTypePayload.Recon)
        )

      val bankAccount = createAccount(
        AccountPayload(name = "Bank", ledgerId = ledger.id.get, accountType = AccountTypePayload.Secondary)
      )

      // A single BAI2 feed covers visa's account activity; reconToBank stays on its own CSV feed.
      val baiFeed = createFileFeed(
        FileFeedPayload(
          expectedCadenceHours = Some(24),
          name                 = "BAI Feed",
          dataSourceConfig = Some(
            DataSourceConfig(
              parserConfig     = BaiParserConfig(),
              connectionConfig = S3ConnectionConfig(s3Bucket = "scenario1", filePath = "bai")
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
              parserConfig = CsvParserConfig(
                hasHeader         = false,
                columnDefinitions = Map(0 -> "txn_id", 1 -> "amount"),
                amountField       = Some(1)
              ),
              connectionConfig = S3ConnectionConfig(
                s3Bucket = "scenario1",
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

      createReconSpec(
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
