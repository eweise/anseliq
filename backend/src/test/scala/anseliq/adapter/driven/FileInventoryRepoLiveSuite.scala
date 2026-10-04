package anseliq.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.domain.DataSetup
import anseliq.domain.model.{FileInventory, FileInventoryStatus, InMemoryFileSourceEntry}
import anseliq.testing.rollBackOnly

import java.time.OffsetDateTime
import javax.sql.DataSource

class FileInventoryRepoLiveSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  test("lastProcessedLine and PROCESSING status round-trip through the database"):
    val fileInventoryRepo = FileInventoryRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val fileInventory = FileInventory(
          fileFeedId          = data.journalToReconFeed.id,
          sourceSystem        = "S3",
          fileSourceEntryJson = upickle.write(InMemoryFileSourceEntry("test-file")),
          fileSizeBytes       = 100L,
          lastModifiedAt      = OffsetDateTime.now(),
          status              = FileInventoryStatus.QUEUED,
          lastProcessedAt     = None,
          updatedAt           = None
        )
        fileInventoryRepo.insert(fileInventory)

        fileInventoryRepo.update(
          fileInventory.copy(
            status            = FileInventoryStatus.PROCESSING,
            lastProcessedLine = Some(249L)
          )
        )

        val found = fileInventoryRepo.findById(fileInventory.id).get
        assertEquals(found.status, FileInventoryStatus.PROCESSING)
        assertEquals(found.lastProcessedLine, Some(249L))

  test("findAllWithFileFeedName joins in the owning file feed's name"):
    val fileInventoryRepo = FileInventoryRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.fileFeedRepo.update(data.journalToReconFeed.copy(name = "Journal Import"))
        val fileInventory = FileInventory(
          fileFeedId          = data.journalToReconFeed.id,
          sourceSystem        = "S3",
          fileSourceEntryJson = upickle.write(InMemoryFileSourceEntry("test-file")),
          fileSizeBytes       = 100L,
          lastModifiedAt      = OffsetDateTime.now(),
          status              = FileInventoryStatus.QUEUED,
          lastProcessedAt     = None,
          updatedAt           = None
        )
        fileInventoryRepo.insert(fileInventory)

        val results = fileInventoryRepo.findAllWithFileFeedName()
        val found   = results.find((fi, _) => fi.id == fileInventory.id)
        assertEquals(found.map((_, name) => name), Some("Journal Import"))
