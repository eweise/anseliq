package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.*
import onparity.testing.rollBackOnly

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.sql.DataSource

class IngestorSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileFeedRepo: FileFeedRepo = FileFeedRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given disputeRepo: DisputeRepo = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given ledger: Ledger = Ledger()

  def stubSource(entries: Vector[FileSourceEntry], system: String = "S3") = new FileSource:
    val sourceSystem = system
    def listFiles(): Vector[FileSourceEntry] = entries
    override def getFile(entry: FileSourceEntry): InputStream = ???

  private def createExistingFileInventory(fileFeedID: UUID) =
    val s3Entry = S3FileSourceEntry(
      bucket         = "bucket",
      key            = "existing",
      fileSizeBytes  = 512L,
      lastModifiedAt = clockService.now
    )
    FileInventory(
      sourceSystem        = "S3",
      fileFeedId          = fileFeedID,
      fileSourceEntryJson = upickle.write(s3Entry),
      fileSizeBytes       = 512L,
      lastModifiedAt      = clockService.now,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = None,
      updatedAt           = None
    )

  test("detectAndQueue inserts new files with QUEUED status"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "file1.csv", 1024L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "file2.csv", 2048L, clockService.now)
        )
        val result = ingestor.detectAndQueue(UUID.randomUUID(), stubSource(entries))
        assertEquals(result.size, 2)
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))

  test("detectAndQueue skips files already in the inventory"):
    transact(transactor):
      rollBackOnly:
        val fileFeedID = UUID.randomUUID()
        val ingestor   = Ingestor()
        val existing   = createExistingFileInventory(fileFeedID)
        fileInventoryRepo.insert(existing)
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "existing", 512L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "new", 1024L, clockService.now)
        )
        val result = ingestor.detectAndQueue(fileFeedID, stubSource(entries))
        assertEquals(result.size, 1)
        val foundKey = result.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key
        assertEquals(foundKey, "new")

  test("detectAndQueue returns empty when all source files are already in inventory"):
    transact(transactor):
      rollBackOnly:
        val fileFeedID = UUID.randomUUID()
        val ingestor   = Ingestor()
        fileInventoryRepo.insert(createExistingFileInventory(fileFeedID))
        val result = ingestor.detectAndQueue(
          fileFeedID,
          stubSource(Vector(S3FileSourceEntry("bucket", "existing", 512L, clockService.now)))
        )
        assert(result.isEmpty)

  private def csvFeed(): Txn[FileFeed] =
    val feed = FileFeed(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = true,
            columnDefinitions = Map(0 -> "id", 1 -> "amount"),
            amountField       = Some(1)
          )
        )
      )
    )
    fileFeedRepo.insert(feed)
    feed

  test("testParse: parses rows against the feed's parser config without writing anything"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val feed     = csvFeed()

        val content = "id,amount\n1,100.50\n2,200.25\n"
        val result  = ingestor.testParse(feed, content)

        assertEquals(result.errors, Seq.empty)
        assertEquals(result.truncated, false)
        assertEquals(result.linesProcessed, 3, "header + 2 data lines")
        assertEquals(result.rows.size, 2)
        assertEquals(result.rows.map(_.amount), Seq(Some(BigDecimal("100.50")), Some(BigDecimal("200.25"))))
        assertEquals(result.rows.head.props, Map("id" -> "1", "amount" -> "100.50"))

        assert(movementRepo.findByAccount(data.reconAccount.id).isEmpty, "testParse must not write any movements")

  test("testParse: a malformed amount is captured as an error for that line, without aborting the rest"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = csvFeed()

        val content = "id,amount\n1,not-a-number\n2,200.25\n"
        val result  = ingestor.testParse(feed, content)

        assertEquals(result.rows.size, 1)
        assertEquals(result.rows.head.props("id"), "2")
        assertEquals(result.errors.size, 1)
        assertEquals(result.errors.head.lineNumber, 1L)

  test("testParse: caps at maxLines and reports truncated"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = csvFeed()

        val content = ("id,amount" +: (1 to 5).map(i => s"$i,$i.00")).mkString("\n")
        val result  = ingestor.testParse(feed, content, maxLines = 3)

        assertEquals(result.linesProcessed, 3)
        assertEquals(result.truncated, true)
        assertEquals(result.rows.size, 2, "header + 2 data lines within the 3-line cap")

  private def openFileMissingDisputes(referenceId: UUID): Txn[Vector[Dispute]] =
    disputeRepo.findAll.filter(d => d.referenceId == referenceId && d.category == DisputeCategory.FileMissing)

  test("checkForMissingFile does nothing when expectedCadenceHours isn't configured"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = FileFeed()
        fileFeedRepo.insert(feed)
        clockService.addHours(1000)

        ingestor.checkForMissingFile(feed)
        assert(openFileMissingDisputes(feed.id).isEmpty)

  test("checkForMissingFile raises nothing while the most recent file is still within cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = FileFeed(expectedCadenceHours = Some(24))
        fileFeedRepo.insert(feed)
        fileInventoryRepo.insert(createExistingFileInventory(feed.id))

        ingestor.checkForMissingFile(feed)
        assert(openFileMissingDisputes(feed.id).isEmpty)

  test("checkForMissingFile raises a FileMissing dispute once the most recent file ages past cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = FileFeed(expectedCadenceHours = Some(24))
        fileFeedRepo.insert(feed)
        fileInventoryRepo.insert(createExistingFileInventory(feed.id))

        clockService.addHours(25)
        ingestor.checkForMissingFile(feed)

        val disputes = openFileMissingDisputes(feed.id)
        assertEquals(disputes.size, 1)
        assertEquals(disputes.head.targetType, DisputeTarget.FileFeed)
        assertEquals(disputes.head.status, DisputeStatus.New)

        // Rerunning the check (e.g. the next scan cycle) must not raise a second dispute.
        ingestor.checkForMissingFile(feed)
        assertEquals(openFileMissingDisputes(feed.id).size, 1)

  test("checkForMissingFile resolves an open dispute once a new file arrives within cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = FileFeed(expectedCadenceHours = Some(24))
        fileFeedRepo.insert(feed)
        fileInventoryRepo.insert(createExistingFileInventory(feed.id))

        clockService.addHours(25)
        ingestor.checkForMissingFile(feed)
        val raised = openFileMissingDisputes(feed.id)
        assertEquals(raised.size, 1)
        assertEquals(raised.head.status, DisputeStatus.New)

        // A fresh file lands (e.g. detectAndQueue found a new one this cycle) — its lastModifiedAt
        // is "now", so the feed is back within cadence.
        fileInventoryRepo.insert(createExistingFileInventory(feed.id))
        ingestor.checkForMissingFile(feed)

        val afterResolve = disputeRepo.findById(raised.head.id).get
        assertEquals(afterResolve.status, DisputeStatus.Resolved)

        val comments = disputeCommentRepo.findByDisputeId(raised.head.id)
        assertEquals(comments.size, 1)
        assertEquals(comments.head.newStatus, Some(DisputeStatus.Resolved))

        // Rerunning the check again (still within cadence) must not re-resolve or add a second comment.
        ingestor.checkForMissingFile(feed)
        assertEquals(disputeCommentRepo.findByDisputeId(raised.head.id).size, 1)

  test("checkForMissingFile falls back to the feed's own createdAt when no file has ever arrived"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val feed     = FileFeed(expectedCadenceHours = Some(24))
        fileFeedRepo.insert(feed)
        val persisted = fileFeedRepo.findById(feed.id).get
        assert(
          persisted.createdAt.isDefined,
          "createdAt should be populated by the update_file_feed trigger on insert"
        )

        clockService.addHours(25)
        ingestor.checkForMissingFile(persisted)

        assertEquals(openFileMissingDisputes(feed.id).size, 1)
