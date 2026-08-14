package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.domain.DataSetup
import onparity.domain.model.{FileInventory, FileInventoryStatus, InMemoryFileSourceEntry}
import onparity.testing.rollBackOnly

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
          movementSpecId      = data.journalToRecon.id,
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
