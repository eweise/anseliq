package perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import perfectpair.domain.model.FileInventory
import perfectpair.domain.port.driven.FileInventoryRepo

import java.util.UUID

class FileInventoryRepoLive extends Repo[FileInventory, FileInventory, UUID] with FileInventoryRepo:
  val fi = FileInventory.Table

  def findBySourceSystem(sourceSystem: String)(using DbCon): Set[FileInventory] =
    sql"""
    SELECT ${fi.all} 
    FROM $fi WHERE source_system = $sourceSystem
    """
      .query[FileInventory]
      .run()
      .toSet
