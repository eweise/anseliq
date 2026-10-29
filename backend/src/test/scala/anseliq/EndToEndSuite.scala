package anseliq

import anseliq.common.model.*
import anseliq.common.model.Interval.Day
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.model.Relationship.OneToMany
import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class EndToEndSuite extends munit.FunSuite {

  // Reuses Main's exact wiring (AppContainer) instead of hand-duplicating it here, so this suite
  // can't drift out of sync with production wiring the way it previously did.
  private val app = TestAppContainer()

  // ingestResumable (unlike the now-deleted Ingestor.ingest) commits its own batches in their own
  // transactions rather than participating in the caller's, so these tests can no longer rely on
  // rollBackOnly to undo their setup: everything below is committed for real and must be torn down
  // explicitly. reconciliation has no cascade from reconciliation_spec (and reconciliation_spec none
  // from account), so those go first; ledger's cascade then takes care of the accounts,
  // movement specs, and any movements still attached to them.
  private def teardown(
    reconSpec:      ReconciliationSpec,
    ledger:         Ledger,
    movementSpecs:  Seq[MovementSpec],
    reconAccountId: ID
  ): Unit =
    transact(app.transactor):
      app.reconRepo.findByReconSpecID(reconSpec.id).foreach(r => app.reconRepo.deleteById(r.id))
      app.reconSpecRepo.deleteById(reconSpec.id)
      // .event[T] deserializes the stored JSON payload, which can fail for events published by an
      // older, incompatible version of the case class (e.g. a real event from a long-running server
      // process using different code) — those aren't this test's events, so skip rather than crash.
      app.eventRepo
        .findByEventType("AccountReconciled")
        .filter(es => scala.util.Try(es.event[AccountReconciled].accountID == reconAccountId).getOrElse(false))
        .foreach(es => app.eventRepo.deleteById(es.id))
      val movementSpecIds = movementSpecs.map(_.id).toSet
      app.eventRepo
        .findByEventType("MoneyMovementsIngested")
        .filter(es =>
          scala.util.Try(movementSpecIds.contains(es.event[MoneyMovementsIngested].movementSpecID)).getOrElse(false)
        )
        .foreach(es => app.eventRepo.deleteById(es.id))
      app.ledgerRepo.deleteById(ledger.id)

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

    val (
      ledger,
      reconAccount,
      journalAccount,
      reportAccount,
      journalFeed,
      journalToRecon,
      reportFeed,
      reportToRecon,
      reconSpec
    ) =
      transact(app.transactor):
        val ledger = Ledger(name = createID().toString)
        app.ledgerRepo.insert(ledger)

        val reconAccount = Account(name = "recon", ledgerId = ledger.id, accountType = AccountType.Recon)
        app.accountRepo.insert(reconAccount)

        val journalAccount =
          Account(name = "journal", ledgerId = ledger.id, accountType = AccountType.Primary)
        app.accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", ledgerId = ledger.id, accountType = AccountType.Secondary)
        app.accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        app.movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(reportFeed)
        val reportToRecon = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
        )
        app.movementSpecRepo.insert(reportToRecon)

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
        app.reconSpecRepo.insert(reconSpec)

        (
          ledger,
          reconAccount,
          journalAccount,
          reportAccount,
          journalFeed,
          journalToRecon,
          reportFeed,
          reportToRecon,
          reconSpec
        )

    try
      // Ingest some files
      app.ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reportToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile1.getBytes(UTF_8))
      )
      app.ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile1.getBytes(UTF_8))
      )

      // Reconcile the data
      app.reconciler.reconcileAccountResumable(reconAccount.id)

      transact(app.transactor):
        val unreconciledReconAccounts = app.movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = app.movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = app.movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = app.reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
    finally teardown(reconSpec, ledger, Seq(journalToRecon, reportToRecon), reconAccount.id)
  }

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

    val (
      ledger,
      reconAccount,
      journalAccount,
      reportAccount,
      journalFeed,
      journalToRecon,
      reportFeed,
      reportToRecon,
      reconSpec
    ) =
      transact(app.transactor):
        val ledger = Ledger(name = createID().toString)
        app.ledgerRepo.insert(ledger)

        val reconAccount = Account(name = "recon", ledgerId = ledger.id, accountType = AccountType.Recon)
        app.accountRepo.insert(reconAccount)

        val journalAccount =
          Account(name = "journal", ledgerId = ledger.id, accountType = AccountType.Primary)
        app.accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", ledgerId = ledger.id, accountType = AccountType.Secondary)
        app.accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        app.movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(reportFeed)
        val reportToRecon = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
        )
        app.movementSpecRepo.insert(reportToRecon)

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
        app.reconSpecRepo.insert(reconSpec)

        (
          ledger,
          reconAccount,
          journalAccount,
          reportAccount,
          journalFeed,
          journalToRecon,
          reportFeed,
          reportToRecon,
          reconSpec
        )

    try
      // Ingest some files
      app.ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reportToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile1.getBytes(UTF_8))
      )
      app.ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile1.getBytes(UTF_8))
      )

      // Reconcile the data
      app.reconciler.reconcileAccountResumable(reconAccount.id)

      transact(app.transactor):
        val unreconciledReconAccounts = app.movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = app.movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = app.movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = app.reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
    finally teardown(reconSpec, ledger, Seq(journalToRecon, reportToRecon), reconAccount.id)
  }

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

    val (
      ledger,
      reconAccount,
      _,
      _,
      journalFeed,
      journalToRecon,
      reportFeed,
      reconToReport,
      reconSpec
    ) =
      transact(app.transactor):
        val ledger = Ledger(name = createID().toString)
        app.ledgerRepo.insert(ledger)

        val reconAccount = Account(name = "recon", ledgerId = ledger.id, accountType = AccountType.Recon)
        app.accountRepo.insert(reconAccount)

        val journalAccount =
          Account(name = "journal", ledgerId = ledger.id, accountType = AccountType.Primary)
        app.accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", ledgerId = ledger.id, accountType = AccountType.Secondary)
        app.accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        app.movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
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
        app.fileFeedRepo.insert(reportFeed)
        val reconToReport = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
        )
        app.movementSpecRepo.insert(reconToReport)

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
        app.reconSpecRepo.insert(reconSpec)

        (
          ledger,
          reconAccount,
          journalAccount,
          reportAccount,
          journalFeed,
          journalToRecon,
          reportFeed,
          reconToReport,
          reconSpec
        )

    try
      // Ingest some files
      app.ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reconToReport),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile.getBytes(UTF_8))
      )
      app.ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile.getBytes(UTF_8))
      )

      // Reconcile the data
      app.reconciler.reconcileAccountResumable(reconAccount.id)

      transact(app.transactor):
        val updateReconAccount = app.accountRepo.findById(reconAccount.id).get
        assertEquals(updateReconAccount.balance, BigDecimal(250), "reconAccount.balance 250")
        // Scoped to this test's own reconSpec: app.reconRepo.findAll would pick up every other
        // reconciliation ever committed against this shared dev database.
        val reconciliations = app.reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.size, 1, "recon size = 1")

        // add up movement from reconcilation. See if the amount matches
        val reconciledMovements = app.movementRepo.findByReconciliationID(reconciliations.head.id)
        assertEquals(reconciledMovements.size, 3)
        // all movement amounts should add up to 0
        val totalAmount = reconciledMovements.foldLeft(BigDecimal(0))((accum, movement) =>
          if movement.inAccountId == reconAccount.id
          then accum + movement.amount
          else accum - movement.amount
        )
        assertEquals(totalAmount, BigDecimal(0))

        val unreconciled = app.movementRepo.findUnReconciledByAccount(reconAccount.id);
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
    finally teardown(reconSpec, ledger, Seq(journalToRecon, reconToReport), reconAccount.id)
  }
}
