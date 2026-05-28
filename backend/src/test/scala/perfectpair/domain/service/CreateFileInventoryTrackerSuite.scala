package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, FileInventoryRepoLive}
import perfectpair.domain.model.{FileInventory, FileInventoryStatus}
import perfectpair.domain.port.driven.{FileSource, FileSourceEntry}
import perfectpair.testing.rollBackOnly

import java.time.OffsetDateTime
import javax.sql.DataSource

class CreateFileInventoryTrackerSuite extends munit.FunSuite:

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given transactor: Transactor = Transactor(dataSource)

  val now = OffsetDateTime.now()

  def stubSource(entries: Vector[FileSourceEntry], system: String = "S3") = new FileSource:
    val sourceSystem             = system
    def listFiles(): Vector[FileSourceEntry] = entries

  test("detectAndQueue inserts new files with QUEUED status"):
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val entries = Vector(
          FileSourceEntry("s3://bucket/file1.csv", 1024L, now),
          FileSourceEntry("s3://bucket/file2.csv", 2048L, now)
        )
        val result = tracker.detectAndQueue(stubSource(entries))
        assert(result.size == 2)
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))
        assert(repo.findAll.size == 2)

  test("detectAndQueue skips files already in the inventory"):
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val existing = FileInventory(
          sourceSystem    = "S3",
          filePath        = "s3://bucket/existing.csv",
          fileSizeBytes   = 512L,
          lastModifiedAt  = now,
          status          = FileInventoryStatus.QUEUED,
          lastProcessedAt = None,
          updatedAt       = None
        )
        repo.insert(existing)
        val entries = Vector(
          FileSourceEntry("s3://bucket/existing.csv", 512L, now),
          FileSourceEntry("s3://bucket/new.csv", 1024L, now)
        )
        val result = tracker.detectAndQueue(stubSource(entries))
        assert(result.size == 1)
        assert(result.head.filePath == "s3://bucket/new.csv")

  test("detectAndQueue returns empty when all source files are already in inventory"):
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val existing = FileInventory(
          sourceSystem    = "S3",
          filePath        = "s3://bucket/file.csv",
          fileSizeBytes   = 512L,
          lastModifiedAt  = now,
          status          = FileInventoryStatus.PROCESSING,
          lastProcessedAt = None,
          updatedAt       = None
        )
        repo.insert(existing)
        val result = tracker.detectAndQueue(stubSource(Vector(
          FileSourceEntry("s3://bucket/file.csv", 512L, now)
        )))
        assert(result.isEmpty)
