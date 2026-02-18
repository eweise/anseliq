package perfectpair

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.*
import perfectpair.common.model.Interval.Day
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.{Ingestor, Reconciler}
import perfectpair.testing.rollBackOnly

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class EndToEndSuite extends munit.FunSuite {
  val fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  val movementRepo:       MovementRepo           = MovementRepoLive()
  val movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  val reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  val reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  val accountRepo:        AccountRepo            = AccountRepoLive()
  val ingestor:           Ingestor               = Ingestor(movementRepo = movementRepo)
  val reconciler: Reconciler = Reconciler(
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    reconSpecRepo      = reconSpecRepo,
    movementSpecRepo   = movementSpecRepo,
    reconRepo          = reconRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )

  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  test("1 to 1 attribute teset") {
    val journalFile1 =
      """record_date, transaction_id, amount, description
        |2025-03-04,123,120.50,posted to bank
        |2025-03-04,456,-50,reversal
        |2025-03-05,789,100.11,another posting
        |2025-03-05,987,50.89,correction
        |""".stripMargin

    val reportFile1 =
      """2025-03-05,123,120.50
        |2025-03-06,789,100.11
        |2025-03-06,987,50.89
        |""".stripMargin

    transact(transactor) {
      rollBackOnly {

        val fundFlowConfig = FundFlowConfig(name = createID().toString)
        fundFlowConfigRepo.insert(fundFlowConfig)

        val reconAccount = Account(name = "recon", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reconAccount)

        val journalAccount = Account(name = "journal", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reportAccount)

        val journalToRecon = MovementSpec(
          name         = "journal to recon",
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = true,
                columnDefinitions = Map(
                  0 -> "record_date",
                  1 -> "transaction_id",
                  2 -> "amount",
                  3 -> "description"
                ),
                amountField = Some(2)
              )
            )
          )
        )
        movementSpecRepo.insert(journalToRecon)

        val reportToRecon = MovementSpec(
          name         = "report to recon",
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = false,
                columnDefinitions = Map(
                  0 -> "txn_date",
                  1 -> "txn_id",
                  2 -> "amount"
                ),
                amountField = Some(2)
              )
            )
          )
        )
        movementSpecRepo.insert(reportToRecon)

        val reconSpec: ReconciliationSpec = ReconciliationSpec(
          fundFlowId = fundFlowConfig.id,
          matchingRulesJson = upickle.write(
            EqualityMatcher(
              AttributeMatchRule(
                inMovementSpecID  = journalToRecon.id,
                outMovementSpecID = reportToRecon.id,
                inKey             = StringAttribute(key = "transaction_id"),
                outKey            = StringAttribute(key = "txn_id")
              )
            )
          )
        )
        reconSpecRepo.insert(reconSpec)

        // Ingest some files
        ingestor.ingest(reportToRecon, ByteArrayInputStream(reportFile1.getBytes(UTF_8)))
        ingestor.ingest(journalToRecon, ByteArrayInputStream(journalFile1.getBytes(UTF_8)))

        // Reconcile the data
        reconciler.reconcileFundFlow(fundFlowConfig)

        val unreconciledReconAccounts = movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
      } // rollback
    }   // transaction
  }     // test

  test("1 to 1 where report date 1 day greater than journal date") {
    val journalFile1 =
      """record_date, transaction_id, amount, description
        |2025-03-04,123,120.50,posted to bank
        |2025-03-04,456,-50,reversal
        |2025-03-05,789,100.11,another posting
        |2025-03-05,987,50.89,correction
        |""".stripMargin

    val reportFile1 =
      """2025-03-05,123,120.50
        |2025-03-06,789,100.11
        |2025-03-06,987,50.89
        |""".stripMargin

    transact(transactor) {
      rollBackOnly {

        val fundFlowConfig = FundFlowConfig(name = createID().toString)
        fundFlowConfigRepo.insert(fundFlowConfig)

        val reconAccount = Account(name = "recon", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reconAccount)

        val journalAccount = Account(name = "journal", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reportAccount)

        val journalToRecon = MovementSpec(
          name         = "journal to recon",
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = true,
                columnDefinitions = Map(
                  0 -> "record_date",
                  1 -> "transaction_id",
                  2 -> "amount",
                  3 -> "description"
                ),
                amountField = Some(2)
              )
            )
          )
        )
        movementSpecRepo.insert(journalToRecon)

        val reportToRecon = MovementSpec(
          name         = "report to recon",
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = false,
                columnDefinitions = Map(
                  0 -> "txn_date",
                  1 -> "txn_id",
                  2 -> "amount"
                ),
                amountField = Some(2)
              )
            )
          )
        )
        movementSpecRepo.insert(reportToRecon)

        val reconSpec: ReconciliationSpec = ReconciliationSpec(
          fundFlowId = fundFlowConfig.id,
          matchingRulesJson = upickle.write(
            EqualityMatcher(
              AndRule(
                List(
                  AttributeMatchRule(
                    inMovementSpecID  = journalToRecon.id,
                    outMovementSpecID = reportToRecon.id,
                    inKey             = StringAttribute(key = "transaction_id"),
                    outKey            = StringAttribute(key = "txn_id")
                  ),
                  AttributeMatchRule(
                    inMovementSpecID  = journalToRecon.id,
                    outMovementSpecID = reportToRecon.id,
                    inKey             = DateAttribute(key = "record_date"),
                    outKey            = DateAttribute(key = "txn_date", DateAdd(Day, -1))
                  )
                )
              )
            )
          )
        )
        reconSpecRepo.insert(reconSpec)

        // Ingest some files
        ingestor.ingest(reportToRecon, ByteArrayInputStream(reportFile1.getBytes(UTF_8)))
        ingestor.ingest(journalToRecon, ByteArrayInputStream(journalFile1.getBytes(UTF_8)))

        // Reconcile the data
        reconciler.reconcileFundFlow(fundFlowConfig)

        val unreconciledReconAccounts = movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
      } // rollback
    }   // transaction
  }     // test
}
