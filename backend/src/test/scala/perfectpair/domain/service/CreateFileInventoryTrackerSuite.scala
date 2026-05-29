package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, EventRepoLive, FileInventoryRepoLive}
import perfectpair.domain.model.{FileInventory, FileInventoryStatus, FileSourceEntry, S3FileSourceEntry}
import perfectpair.domain.port.driven.{EventRepo, FileSource}
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

given clockService: ClockService = ClockService()

class CreateFileInventoryTrackerSuite extends munit.FunSuite:
  val now = clockService.now
  given eventRepo:  EventRepo  = EventRepoLive()
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def stubSource(entries: Vector[FileSourceEntry], system: String = "S3") = new FileSource:
    val sourceSystem = system
    def listFiles(): Vector[FileSourceEntry] = entries

  given eventStore: EventStore = EventStore()

  private def createExistingFileInventory(movementSpecID: UUID) = {
    val s3FileSourceEntry = S3FileSourceEntry(
      bucket         = "bucket",
      key            = "existing",
      fileSizeBytes  = 512L,
      lastModifiedAt = clockService.now
    )
    val fileSourceEntryJson = upickle.write(s3FileSourceEntry)
    val existing = FileInventory(
      sourceSystem        = "S3",
      movementSpecId      = movementSpecID,
      fileSourceEntryJson = fileSourceEntryJson,
      fileSizeBytes       = 512L,
      lastModifiedAt      = clockService.now,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = None,
      updatedAt           = None
    )
    existing
  }

  test("detectAndQueue inserts new files with QUEUED status"):
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo, eventStore)
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "file1.csv", 1024L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "file2.csv", 2048L, clockService.now)
        )
        val result = tracker.detectAndQueue(UUID.randomUUID(), stubSource(entries))
        assert(result.size == 2)
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))
        assert(repo.findAll.size == 2)

  test("detectAndQueue skips files already in the inventory"):
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val repo           = FileInventoryRepoLive()
        val tracker        = FileInventoryTracker(using repo)
        val existing: FileInventory = createExistingFileInventory(movementSpecID)
        repo.insert(existing)
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "existing", 512L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "new", 1024L, clockService.now)
        )
        val result = tracker.detectAndQueue(movementSpecID, stubSource(entries))
        assert(result.size == 1)
        val foundKey = result.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key
        assert(foundKey == "new")

  test("detectAndQueue returns empty when all source files are already in inventory"):
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val repo           = FileInventoryRepoLive()
        val tracker        = FileInventoryTracker(using repo)
        repo.insert(createExistingFileInventory(movementSpecID))
        val result = tracker.detectAndQueue(
          movementSpecID,
          stubSource(
            Vector(
              S3FileSourceEntry("bucket", "existing", 512L, clockService.now)
            )
          )
        )
        assert(result.isEmpty)
