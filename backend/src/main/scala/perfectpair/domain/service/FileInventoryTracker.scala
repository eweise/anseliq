package perfectpair.domain.service

import perfectpair.common.model.model.ID
import perfectpair.domain.model.FileInventoryStatus.COMPLETED
import perfectpair.domain.model.{ErrIfNotFound, FileInventory, FileInventoryStatus, NewFileDetected}
import perfectpair.domain.port.driven.{FileInventoryRepo, FileSource, Txn}

class FileInventoryTracker(using
  fileInventoryRepo: FileInventoryRepo,
  eventStore:        EventStore
):

  def detectAndQueue(movementSpecID: ID, source: FileSource): Txn[Vector[FileInventory]] =
    val existingFileSourceEntries = fileInventoryRepo
      .findBySourceSystem(source.sourceSystem)
      .map(inventory => inventory.fileSourceEntry())

    val newFiles = source.listFiles().filterNot(f => existingFileSourceEntries.contains(f))

    val toInsert = newFiles.map(f =>
      val fileSourceEntryJson = upickle.write(f)
      val fileInventory = FileInventory(
        movementSpecId      = movementSpecID,
        sourceSystem        = source.sourceSystem,
        fileSourceEntryJson = fileSourceEntryJson,
        fileSizeBytes       = f.fileSizeBytes,
        lastModifiedAt      = f.lastModifiedAt,
        status              = FileInventoryStatus.QUEUED,
        lastProcessedAt     = None,
        updatedAt           = None
      )

      fileInventoryRepo.insert(fileInventory)
      eventStore.publishEvent(NewFileDetected(fileInventoryID = fileInventory.id))
      fileInventory
    )
    toInsert

  def markComplete(fileInventoryID: ID): Txn[Unit] = fileInventoryRepo.update(
    fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)
      .copy(status = COMPLETED)
  )
