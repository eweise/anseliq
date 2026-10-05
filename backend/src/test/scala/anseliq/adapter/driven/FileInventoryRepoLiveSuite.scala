package anseliq.adapter.driven

import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact
import anseliq.domain.DataSetup
import anseliq.domain.model.{FileInventory, FileInventoryStatus, InMemoryFileSourceEntry}
import anseliq.testing.rollBackOnly

import java.time.OffsetDateTime

class FileInventoryRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("lastProcessedLine and PROCESSING status round-trip through the database"):
    val fileInventoryRepo = app.fileInventoryRepo
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
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
    val fileInventoryRepo = app.fileInventoryRepo
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
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
