package perfectpair.domain.service

import perfectpair.domain.model.{FileInventory, FileInventoryStatus}
import perfectpair.domain.port.driven.{FileInventoryRepo, FileSource, Txn}

class FileInventoryTracker(using fileInventoryRepo: FileInventoryRepo):

  def detectAndQueue(source: FileSource): Txn[Vector[FileInventory]] =
    val existingPaths = fileInventoryRepo.findPathsBySourceSystem(source.sourceSystem)
    val newFiles      = source.listFiles().filterNot(f => existingPaths.contains(f.filePath))
    val toInsert = newFiles.map(f =>
      FileInventory(
        sourceSystem    = source.sourceSystem,
        filePath        = f.filePath,
        fileSizeBytes   = f.fileSizeBytes,
        lastModifiedAt  = f.lastModifiedAt,
        status          = FileInventoryStatus.QUEUED,
        lastProcessedAt = None,
        updatedAt       = None
      )
    )
    fileInventoryRepo.insertAll(toInsert)
    toInsert
