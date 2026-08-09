package perfectpair

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.*
import perfectpair.common.model.Interval.Day
import perfectpair.domain.model.*
import perfectpair.domain.model.Relationship.OneToMany
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.*
import perfectpair.testing.rollBackOnly

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class EndToEndSuite extends munit.FunSuite {
  given clock: ClockService = ClockServiceLive()

  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given movementRepo:       MovementRepo           = MovementRepoLive()
  given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  given reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  given accountRepo:        AccountRepo            = AccountRepoLive()
  given eventRepo:          EventRepo              = EventRepoLive()
  given eventStore:         EventStore             = EventStore()
  given fileInventoryRepo:  FileInventoryRepo      = FileInventoryRepoLive()
  given fileSourceFactory:  FileSourceFactory      = S3FileSourceFactory()
  given ledger:             Ledger                 = Ledger()
  given ingestor:           Ingestor               = Ingestor()
  given reconciler:         InMemoryReconciler     = InMemoryReconciler()

  test("1 to 1 attribute test") {
    val journalFile1 =
      """record_date, transaction_id, amount, description
        |2025-03-04,456,-50,reversal
        |2025-03-05,789,100.11,another posting
        |2025-03-05,987,50.89,correction
        |2025-03-04,123,120.50,posted to bank
        |
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
          accountId         = reconAccount.id,
          inMovementSpecId  = journalToRecon.id,
          outMovementSpecId = reportToRecon.id,
          ruleJson = upickle.write(
            StringAttributeMatchRule(
              inKey  = StringAttribute(key = "transaction_id"),
              outKey = StringAttribute(key = "txn_id")
            )
          )
        )
        reconSpecRepo.insert(reconSpec)

        // Ingest some files
        ingestor.ingest(
          reportToRecon,
          InMemoryFileSourceEntry("report"),
          ByteArrayInputStream(reportFile1.getBytes(UTF_8))
        )
        ingestor.ingest(
          journalToRecon,
          InMemoryFileSourceEntry("journal"),
          ByteArrayInputStream(journalFile1.getBytes(UTF_8))
        )

        // Reconcile the data
        reconciler.reconcileAccount(reconAccount.id)

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
        |2025-03-05,987,50.89,correction
        |2025-03-04,123,120.50,posted to bank
        |2025-03-04,456,-50,reversal
        |2025-03-05,789,100.11,another posting
        |""".stripMargin

    val reportFile1 =
      """2025-03-06,789,100.11
        |2025-03-06,987,50.89
        |2025-03-05,123,120.50
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
          accountId         = reconAccount.id,
          inMovementSpecId  = journalToRecon.id,
          outMovementSpecId = reportToRecon.id,
          ruleJson = upickle.write(
            AndRule(
              List(
                DateAttributeMatchRule(
                  inKey = DateAttribute(key = "record_date"),
                  outKey =
                    DateAttribute(key = "txn_date", Some(DateAdd(Day, -1))) // shouldn't be less than watermark date
                ),
                StringAttributeMatchRule(
                  inKey  = StringAttribute(key = "transaction_id"),
                  outKey = StringAttribute(key = "txn_id")
                )
              )
            )
          )
        )
        reconSpecRepo.insert(reconSpec)

        // Ingest some files
        ingestor.ingest(
          reportToRecon,
          InMemoryFileSourceEntry("report"),
          ByteArrayInputStream(reportFile1.getBytes(UTF_8))
        )
        ingestor.ingest(
          journalToRecon,
          InMemoryFileSourceEntry("journal"),
          ByteArrayInputStream(journalFile1.getBytes(UTF_8))
        )

        // Reconcile the data
        reconciler.reconcileAccount(reconAccount.id)

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

  test(
    "1 to many where journal accounts transactions by date + account have missing record on 3-4 but then reconcile on 03-5"
  ) {
    val journalFile =
      """record_date, account, amount
        |2025-03-04,abc,200
        |2025-03-05,xyz,300
        |""".stripMargin

    val reportFile =
      """2025-03-04,abc,123,150.50
        |2025-03-05,ddd,423,50.00
        |2025-03-04,abc,234,49.50
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
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = true,
                columnDefinitions = Map(
                  0 -> "record_date",
                  1 -> "account",
                  2 -> "amount"
                ),
                amountField = Some(2)
              )
            )
          )
        )
        movementSpecRepo.insert(journalToRecon)

        val reconToReport = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CSVParserConfig(
                hasHeader = false,
                columnDefinitions = Map(
                  0 -> "account_date",
                  1 -> "account_id",
                  2 -> "transaction_id",
                  3 -> "balance"
                ),
                amountField = Some(3)
              )
            )
          )
        )
        movementSpecRepo.insert(reconToReport)

        val reconSpec: ReconciliationSpec = ReconciliationSpec(
          accountId         = reconAccount.id,
          relationship      = OneToMany,
          inMovementSpecId  = journalToRecon.id,
          outMovementSpecId = reconToReport.id,
          ruleJson = upickle.write(
            AndRule(
              List(
                StringAttributeMatchRule(
                  inKey  = StringAttribute(key = "account"),
                  outKey = StringAttribute(key = "account_id")
                ),
                DateAttributeMatchRule(
                  inKey  = DateAttribute(key = "record_date"),
                  outKey = DateAttribute(key = "account_date")
                )
              )
            )
          )
        )
        reconSpecRepo.insert(reconSpec)

        // Ingest some files
        ingestor.ingest(
          reconToReport,
          InMemoryFileSourceEntry("report"),
          ByteArrayInputStream(reportFile.getBytes(UTF_8))
        )
        ingestor.ingest(
          journalToRecon,
          InMemoryFileSourceEntry("journal"),
          ByteArrayInputStream(journalFile.getBytes(UTF_8))
        )

        // Reconcile the data
        reconciler.reconcileAccount(reconAccount.id)
        val allReconMovements  = movementRepo.findByAccount(reconAccount.id)
        val updateReconAccount = accountRepo.findById(reconAccount.id).get
        assertEquals(updateReconAccount.balance, BigDecimal(250), "reconAccount.balance 250")
        val reconciliations = reconRepo.findAll
        assertEquals(reconciliations.size, 1, "recon size = 1")

        // add up movement from reconcilation. See if the amount matches
        val reconciledMovements = movementRepo.findByReconciliationID(reconciliations.head.id)
        assertEquals(reconciledMovements.size, 3)
        // all movement amounts should add up to 0
        val totalAmount = reconciledMovements.foldLeft(BigDecimal(0))((accum, movement) =>
          if movement.inAccountId == reconAccount.id
          then accum + movement.amount
          else accum - movement.amount
        )
        assertEquals(totalAmount, BigDecimal(0))

        val unreconciled = movementRepo.findUnReconciledByAccount(reconAccount.id);
        assertEquals(unreconciled.size, 2)
        val u1 = unreconciled.find(m =>
          m.props.getOrElse("record_date", "") == "2025-03-05"
            && m.props.getOrElse("account", "") == "xyz"
        )
        assert(u1.nonEmpty)
        val u2 = unreconciled.find(m =>
          m.props.getOrElse("account_date", "") == "2025-03-05"
            && m.props.getOrElse("account_id", "") == "ddd"
        )
        assert(u2.nonEmpty)

      } // rollback
    }   // transaction
  }     // test
}
