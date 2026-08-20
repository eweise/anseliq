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
