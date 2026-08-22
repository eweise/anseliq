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
  given transactor:   Transactor   = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource:   DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo:    EventRepo    = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given disputeRepo: DisputeRepo = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given ledger: Ledger = Ledger()

  def stubSource(entries: Vector[FileSourceEntry], system: String = "S3") = new FileSource:
    val sourceSystem = system
    def listFiles(): Vector[FileSourceEntry] = entries
    override def getFile(entry: FileSourceEntry): InputStream = ???

  private def createExistingFileInventory(movementSpecID: UUID) =
    val s3Entry = S3FileSourceEntry(
      bucket         = "bucket",
      key            = "existing",
      fileSizeBytes  = 512L,
      lastModifiedAt = clockService.now
    )
    FileInventory(
      sourceSystem        = "S3",
      movementSpecId      = movementSpecID,
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
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        val existing       = createExistingFileInventory(movementSpecID)
        fileInventoryRepo.insert(existing)
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "existing", 512L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "new", 1024L, clockService.now)
        )
        val result = ingestor.detectAndQueue(movementSpecID, stubSource(entries))
        assertEquals(result.size, 1)
        val foundKey = result.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key
        assertEquals(foundKey, "new")

  test("detectAndQueue returns empty when all source files are already in inventory"):
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        fileInventoryRepo.insert(createExistingFileInventory(movementSpecID))
        val result = ingestor.detectAndQueue(
          movementSpecID,
          stubSource(Vector(S3FileSourceEntry("bucket", "existing", 512L, clockService.now)))
        )
        assert(result.isEmpty)

  private def openFileMissingDisputes(referenceId: UUID): Txn[Vector[Dispute]] =
    disputeRepo.findAll.filter(d => d.referenceId == referenceId && d.category == DisputeCategory.FileMissing)

  test("checkForMissingFile does nothing when expectedCadenceHours isn't configured"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val spec     = MovementSpec(inAccountId = data.reconAccount.id, outAccountId = data.journalAccount.id)
        movementSpecRepo.insert(spec)
        clockService.addHours(1000)

        ingestor.checkForMissingFile(spec)
        assert(openFileMissingDisputes(spec.id).isEmpty)

  test("checkForMissingFile raises nothing while the most recent file is still within cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val spec = MovementSpec(
          inAccountId          = data.reconAccount.id,
          outAccountId         = data.journalAccount.id,
          expectedCadenceHours = Some(24)
        )
        movementSpecRepo.insert(spec)
        fileInventoryRepo.insert(createExistingFileInventory(spec.id))

        ingestor.checkForMissingFile(spec)
        assert(openFileMissingDisputes(spec.id).isEmpty)

  test("checkForMissingFile raises a FileMissing dispute once the most recent file ages past cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val spec = MovementSpec(
          inAccountId          = data.reconAccount.id,
          outAccountId         = data.journalAccount.id,
          expectedCadenceHours = Some(24)
        )
        movementSpecRepo.insert(spec)
        fileInventoryRepo.insert(createExistingFileInventory(spec.id))

        clockService.addHours(25)
        ingestor.checkForMissingFile(spec)

        val disputes = openFileMissingDisputes(spec.id)
        assertEquals(disputes.size, 1)
        assertEquals(disputes.head.targetType, DisputeTarget.MovementSpec)
        assertEquals(disputes.head.status, DisputeStatus.New)

        // Rerunning the check (e.g. the next scan cycle) must not raise a second dispute.
        ingestor.checkForMissingFile(spec)
        assertEquals(openFileMissingDisputes(spec.id).size, 1)

  test("checkForMissingFile resolves an open dispute once a new file arrives within cadence"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val spec = MovementSpec(
          inAccountId          = data.reconAccount.id,
          outAccountId         = data.journalAccount.id,
          expectedCadenceHours = Some(24)
        )
        movementSpecRepo.insert(spec)
        fileInventoryRepo.insert(createExistingFileInventory(spec.id))

        clockService.addHours(25)
        ingestor.checkForMissingFile(spec)
        val raised = openFileMissingDisputes(spec.id)
        assertEquals(raised.size, 1)
        assertEquals(raised.head.status, DisputeStatus.New)

        // A fresh file lands (e.g. detectAndQueue found a new one this cycle) — its lastModifiedAt
        // is "now", so the spec is back within cadence.
        fileInventoryRepo.insert(createExistingFileInventory(spec.id))
        ingestor.checkForMissingFile(spec)

        val afterResolve = disputeRepo.findById(raised.head.id).get
        assertEquals(afterResolve.status, DisputeStatus.Resolved)

        val comments = disputeCommentRepo.findByDisputeId(raised.head.id)
        assertEquals(comments.size, 1)
        assertEquals(comments.head.newStatus, Some(DisputeStatus.Resolved))

        // Rerunning the check again (still within cadence) must not re-resolve or add a second comment.
        ingestor.checkForMissingFile(spec)
        assertEquals(disputeCommentRepo.findByDisputeId(raised.head.id).size, 1)

  test("checkForMissingFile falls back to the spec's own createdAt when no file has ever arrived"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val data     = DataSetup()
        val spec = MovementSpec(
          inAccountId          = data.reconAccount.id,
          outAccountId         = data.journalAccount.id,
          expectedCadenceHours = Some(24)
        )
        movementSpecRepo.insert(spec)
        val persisted = movementSpecRepo.findById(spec.id).get
        assert(persisted.createdAt.isDefined, "createdAt should be populated by the update_movement_spec trigger on insert")

        clockService.addHours(25)
        ingestor.checkForMissingFile(persisted)

        assertEquals(openFileMissingDisputes(spec.id).size, 1)
