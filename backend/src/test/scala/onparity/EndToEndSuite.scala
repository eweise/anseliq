package onparity

import com.augustnagro.magnum.transact
import com.typesafe.config.ConfigFactory
import onparity.app.AppEnv
import onparity.common.model.*
import onparity.common.model.Interval.Day
import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.model.Relationship.OneToMany

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class EndToEndSuite extends munit.FunSuite {

  // Reuses Main's exact adapter wiring instead of hand-duplicating it here, so this suite can't
  // drift out of sync with production wiring the way it previously did.
  private val env = AppEnv(config = ConfigFactory.load("test-application.conf"))
  export env.given

  // ingestResumable (unlike the now-deleted Ingestor.ingest) commits its own batches in their own
  // transactions rather than participating in the caller's, so these tests can no longer rely on
  // rollBackOnly to undo their setup: everything below is committed for real and must be torn down
  // explicitly. reconciliation has no cascade from reconciliation_spec (and reconciliation_spec none
  // from account), so those go first; fund_flow_config's cascade then takes care of the accounts,
  // movement specs, and any movements still attached to them.
  private def teardown(
    reconSpec:      ReconciliationSpec,
    fundFlowConfig: FundFlowConfig,
    movementSpecs:  Seq[MovementSpec],
    reconAccountId: ID
  ): Unit =
    transact(transactor):
      reconRepo.findByReconSpecID(reconSpec.id).foreach(r => reconRepo.deleteById(r.id))
      reconSpecRepo.deleteById(reconSpec.id)
      // .event[T] deserializes the stored JSON payload, which can fail for events published by an
      // older, incompatible version of the case class (e.g. a real event from a long-running server
      // process using different code) — those aren't this test's events, so skip rather than crash.
      eventRepo
        .findByEventType("AccountReconciled")
        .filter(es => scala.util.Try(es.event[AccountReconciled].accountID == reconAccountId).getOrElse(false))
        .foreach(es => eventRepo.deleteById(es.id))
      val movementSpecIds = movementSpecs.map(_.id).toSet
      eventRepo
        .findByEventType("MoneyMovementsIngested")
        .filter(es =>
          scala.util.Try(movementSpecIds.contains(es.event[MoneyMovementsIngested].movementSpecID)).getOrElse(false)
        )
        .foreach(es => eventRepo.deleteById(es.id))
      fundFlowConfigRepo.deleteById(fundFlowConfig.id)

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
      fundFlowConfig,
      reconAccount,
      journalAccount,
      reportAccount,
      journalFeed,
      journalToRecon,
      reportFeed,
      reportToRecon,
      reconSpec
    ) =
      transact(transactor):
        val fundFlowConfig = FundFlowConfig(name = createID().toString)
        fundFlowConfigRepo.insert(fundFlowConfig)

        val reconAccount = Account(name = "recon", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reconAccount)

        val journalAccount = Account(name = "journal", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
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
        fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
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
        fileFeedRepo.insert(reportFeed)
        val reportToRecon = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
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

        (
          fundFlowConfig,
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
      ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reportToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile1.getBytes(UTF_8))
      )
      ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile1.getBytes(UTF_8))
      )

      // Reconcile the data
      reconciler.reconcileAccountResumable(reconAccount.id)

      transact(transactor):
        val unreconciledReconAccounts = movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
    finally teardown(reconSpec, fundFlowConfig, Seq(journalToRecon, reportToRecon), reconAccount.id)
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
      fundFlowConfig,
      reconAccount,
      journalAccount,
      reportAccount,
      journalFeed,
      journalToRecon,
      reportFeed,
      reportToRecon,
      reconSpec
    ) =
      transact(transactor):
        val fundFlowConfig = FundFlowConfig(name = createID().toString)
        fundFlowConfigRepo.insert(fundFlowConfig)

        val reconAccount = Account(name = "recon", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reconAccount)

        val journalAccount = Account(name = "journal", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
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
        fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
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
        fileFeedRepo.insert(reportFeed)
        val reportToRecon = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
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

        (
          fundFlowConfig,
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
      ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reportToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile1.getBytes(UTF_8))
      )
      ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile1.getBytes(UTF_8))
      )

      // Reconcile the data
      reconciler.reconcileAccountResumable(reconAccount.id)

      transact(transactor):
        val unreconciledReconAccounts = movementRepo.findUnReconciledByAccount(reconAccount.id)
        assertEquals(unreconciledReconAccounts.length, 1)

        val unreconciledJournalAccounts = movementRepo.findUnReconciledByAccount(journalAccount.id)
        assertEquals(unreconciledJournalAccounts.length, 1)

        val unreconciledReports = movementRepo.findUnReconciledByAccount(reportAccount.id)
        assertEquals(unreconciledReports.length, 0)

        val reconciliations = reconRepo.findByReconSpecID(reconSpec.id)
        assertEquals(reconciliations.length, 3)
    finally teardown(reconSpec, fundFlowConfig, Seq(journalToRecon, reportToRecon), reconAccount.id)
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
      fundFlowConfig,
      reconAccount,
      journalAccount,
      reportAccount,
      journalFeed,
      journalToRecon,
      reportFeed,
      reconToReport,
      reconSpec
    ) =
      transact(transactor):
        val fundFlowConfig = FundFlowConfig(name = createID().toString)
        fundFlowConfigRepo.insert(fundFlowConfig)

        val reconAccount = Account(name = "recon", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reconAccount)

        val journalAccount = Account(name = "journal", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(journalAccount)

        val reportAccount = Account(name = "report", fundFlowId = fundFlowConfig.id)
        accountRepo.insert(reportAccount)

        val journalFeed = FileFeed(
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
        fileFeedRepo.insert(journalFeed)
        val journalToRecon = MovementSpec(
          inAccountId  = reconAccount.id,
          outAccountId = journalAccount.id,
          fileFeedId   = journalFeed.id
        )
        movementSpecRepo.insert(journalToRecon)

        val reportFeed = FileFeed(
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
        fileFeedRepo.insert(reportFeed)
        val reconToReport = MovementSpec(
          outAccountId = reconAccount.id,
          inAccountId  = reportAccount.id,
          fileFeedId   = reportFeed.id
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

        (
          fundFlowConfig,
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
      ingestor.ingestResumable(
        fileFeed        = reportFeed,
        movementSpecs   = Seq(reconToReport),
        fileSourceEntry = InMemoryFileSourceEntry("report"),
        is              = ByteArrayInputStream(reportFile.getBytes(UTF_8))
      )
      ingestor.ingestResumable(
        fileFeed        = journalFeed,
        movementSpecs   = Seq(journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("journal"),
        is              = ByteArrayInputStream(journalFile.getBytes(UTF_8))
      )

      // Reconcile the data
      reconciler.reconcileAccountResumable(reconAccount.id)

      transact(transactor):
        val updateReconAccount = accountRepo.findById(reconAccount.id).get
        assertEquals(updateReconAccount.balance, BigDecimal(250), "reconAccount.balance 250")
        // Scoped to this test's own reconSpec: reconRepo.findAll would pick up every other
        // reconciliation ever committed against this shared dev database.
        val reconciliations = reconRepo.findByReconSpecID(reconSpec.id)
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
    finally teardown(reconSpec, fundFlowConfig, Seq(journalToRecon, reconToReport), reconAccount.id)
  }
}
