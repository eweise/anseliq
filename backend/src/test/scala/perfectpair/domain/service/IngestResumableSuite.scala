package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.{CSVParserConfig, DataSourceConfig}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

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

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given ledger: Ledger = Ledger()

  private def csvSpec(spec: MovementSpec): MovementSpec =
    spec.copy(
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
      val spec      = csvSpec(data.journalToRecon)
      val ingestor  = Ingestor()
      val is        = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8))

      val result = ingestor.ingestResumable(
        movementSpec    = spec,
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = is,
        batchSize       = 2,
        onBatchComplete = progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.recordCount, 4)
      assertEquals(result.lastProcessedLine, Some(3L))

      val committedMovements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(committedMovements.size, 4, "only the two committed batches (lines 0-3) should be visible")
      assertEquals(committedMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40))
    finally teardown(data)

  test("resuming from lastProcessedLine processes remaining rows exactly once"):
    val data = transact(transactor)(DataSetup())
    try
      val spec     = csvSpec(data.journalToRecon)
      val ingestor = Ingestor()

      val first = ingestor.ingestResumable(
        movementSpec    = spec,
        fileSourceEntry = InMemoryFileSourceEntry("resumable-test"),
        is              = ByteArrayInputStream(fiveRowContent.getBytes(UTF_8)),
        batchSize       = 2,
        onBatchComplete = progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)

      val second = ingestor.ingestResumable(
        movementSpec    = spec,
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
