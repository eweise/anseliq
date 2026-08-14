package onparity.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import onparity.domain.model.FileInventory
import onparity.domain.port.driven.FileInventoryRepo

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
