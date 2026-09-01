package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig, RoutingFilter}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class IngestResumableSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given fileFeedRepo: FileFeedRepo = FileFeedRepoLive()

  given disputeRepo: DisputeRepo = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given ledger: Ledger = Ledger()

  private def csvFeed(): FileFeed =
    FileFeed(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id"),
            amountField       = Some(1)
          )
        )
      )
    )

  // id,amount rows 1..5 -> amounts 10,20,30,40,50
  private def fiveRowContent: String =
    (1 to 5).map(i => s"$i,${i * 10}").mkString("\n")

  private def teardown(data: DataSetup): Unit =
    transact(transactor):
      // ingestResumable publishes real, permanently-committed MoneyMovementsIngested events (not
      // rolled back like the rest of this test's data would be under rollBackOnly) — leaving them
      // behind pollutes the shared event_state table and breaks unrelated tests that pick "the next
      // pending event" (e.g. EventStateStoreSuite), so they need explicit cleanup too.
      val specIds = Set(data.journalToRecon.id, data.reportToRecon.id)
      eventRepo
        .findByEventType("MoneyMovementsIngested")
        .filter(es => specIds.contains(es.event[MoneyMovementsIngested].movementSpecID))
        .foreach(es => eventRepo.deleteById(es.id))
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  test("earlier batches commit durably even when a later batch is rolled back"):
    val data = transact(transactor)(DataSetup())
    try
      val feed     = csvFeed()
      val ingestor = Ingestor()
      val is       = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8))

      val result = ingestor.ingestResumable(
        fileFeed        = feed,
        movementSpecs   = Seq(data.journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = is,
        batchSize       = 2,
        onBatchComplete =
          progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.recordCount, 4)
      assertEquals(result.lastProcessedLine, Some(3L))

      val committedMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(committedMovements.size, 4, "only the two committed batches (lines 0-3) should be visible")
      assertEquals(committedMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40))
    finally teardown(data)

  test("exceeding maxParseErrors stops ingestion and raises a dispute, without discarding earlier batches"):
    val data = transact(transactor)(DataSetup())
    val feed = csvFeed().copy(maxParseErrors = Some(2))
    try
      val ingestor = Ingestor()
      // Valid rows (10, 20, 30) alternate with malformed amounts. batchSize = 1 so each valid row
      // commits as its own batch immediately, making it possible to tell exactly which rows made it
      // in before the 3rd parse error (count > cutoff of 2) stops the run. Row 7 is never reached.
      val content = "1,10\n2,bad\n3,20\n4,bad\n5,30\n6,bad\n7,40"
      val is      = ByteArrayInputStream(content.getBytes(UTF_8))

      val result = ingestor.ingestResumable(
        fileFeed        = feed,
        movementSpecs   = Seq(data.journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("parse-error-cutoff-test"),
        is              = is,
        batchSize       = 1
      )

      assertEquals(result.completed, false)
      assertEquals(result.recordCount, 3, "the three valid rows committed before the cutoff was crossed")

      val committedMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(committedMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30))

      val dispute = transact(transactor):
        disputeRepo.findAll.filter(d => d.referenceId == feed.id && d.category == DisputeCategory.ExcessiveParseErrors)
      assertEquals(dispute.size, 1)
      assertEquals(dispute.head.targetType, DisputeTarget.FileFeed)
      assert(dispute.head.detail.exists(_.contains("3")), "detail should surface the error count that crossed the cutoff")
    finally
      transact(transactor):
        disputeRepo.findAll
          .filter(d => d.referenceId == feed.id && d.category == DisputeCategory.ExcessiveParseErrors)
          .foreach(d => disputeRepo.deleteById(d.id))
      teardown(data)

  test("resuming from lastProcessedLine processes remaining rows exactly once"):
    val data = transact(transactor)(DataSetup())
    try
      val feed     = csvFeed()
      val ingestor = Ingestor()

      val first = ingestor.ingestResumable(
        fileFeed        = feed,
        movementSpecs   = Seq(data.journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8)),
        batchSize       = 2,
        onBatchComplete =
          progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)

      val second = ingestor.ingestResumable(
        fileFeed        = feed,
        movementSpecs   = Seq(data.journalToRecon),
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8)),
        startingLine    = first.lastProcessedLine.map(_ + 1).getOrElse(0L),
        batchSize       = 2
      )
      assertEquals(second.completed, true)
      assertEquals(second.recordCount, 1, "only the previously-uncommitted 5th row")

      val allMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(allMovements.size, 5, "no duplicates and nothing missing")
      assertEquals(allMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40 + 50))
    finally teardown(data)

  test(
    "a multi-account feed routes each row to its matching spec's account pair, and unmatched rows raise one UnroutedRecord dispute"
  ):
    val data     = transact(transactor)(DataSetup())
    val accountA = Account(name = "sub-account-a", fundFlowId = data.fundFlowConfig.id)
    val accountB = Account(name = "sub-account-b", fundFlowId = data.fundFlowConfig.id)
    val feed = FileFeed(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id", 1 -> "amount", 2 -> "account_number"),
            amountField       = Some(1)
          )
        )
      )
    )
    val specA = MovementSpec(
      inAccountId       = data.reconAccount.id,
      outAccountId      = accountA.id,
      fileFeedId        = feed.id,
      routingFilterJson = Some(upickle.write(RoutingFilter(propertyField = "account_number", matchValue = "111")))
    )
    val specB = MovementSpec(
      inAccountId       = data.reconAccount.id,
      outAccountId      = accountB.id,
      fileFeedId        = feed.id,
      routingFilterJson = Some(upickle.write(RoutingFilter(propertyField = "account_number", matchValue = "222")))
    )
    transact(transactor):
      accountRepo.insert(accountA)
      accountRepo.insert(accountB)
      fileFeedRepo.insert(feed)
      movementSpecRepo.insert(specA)
      movementSpecRepo.insert(specB)

    try
      val ingestor = Ingestor()
      // row 1 -> specA (account_number 111), row 2 -> specB (222), row 3 -> no configured spec claims 999
      val content = "1,100,111\n2,200,222\n3,300,999"
      val result = ingestor.ingestResumable(
        fileFeed        = feed,
        movementSpecs   = Seq(specA, specB),
        fileSourceEntry = InMemoryFileSourceEntry("multi-account-test"),
        is              = ByteArrayInputStream(content.getBytes(UTF_8))
      )

      assertEquals(result.recordCount, 2, "only the two routed rows count — the unrouted row isn't recorded")

      val movementsA = transact(transactor)(movementRepo.findByAccount(accountA.id))
      assertEquals(movementsA.size, 1)
      assertEquals(movementsA.head.amount, BigDecimal(100))

      val movementsB = transact(transactor)(movementRepo.findByAccount(accountB.id))
      assertEquals(movementsB.size, 1)
      assertEquals(movementsB.head.amount, BigDecimal(200))

      val unrouted = transact(transactor):
        disputeRepo.findAll.filter(d => d.referenceId == feed.id && d.category == DisputeCategory.UnroutedRecord)
      assertEquals(unrouted.size, 1, "exactly one dispute for the whole run, not one per unrouted row")
      assertEquals(unrouted.head.targetType, DisputeTarget.FileFeed)
      assert(unrouted.head.detail.exists(_.contains("999")), "detail should surface the unmatched account_number value")
    finally
      transact(transactor):
        val specIds = Set(specA.id, specB.id)
        eventRepo
          .findByEventType("MoneyMovementsIngested")
          .filter(es => specIds.contains(es.event[MoneyMovementsIngested].movementSpecID))
          .foreach(es => eventRepo.deleteById(es.id))
        disputeRepo.findAll
          .filter(d => d.referenceId == feed.id && d.category == DisputeCategory.UnroutedRecord)
          .foreach(d => disputeRepo.deleteById(d.id))
        accountRepo.deleteById(accountA.id) // cascades: its movements + specA
        accountRepo.deleteById(accountB.id) // cascades: its movements + specB
        fileFeedRepo.deleteById(feed.id)
      teardown(data)
