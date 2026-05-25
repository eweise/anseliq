package perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import perfectpair.domain.model.FileInventory
import perfectpair.domain.port.driven.FileInventoryRepo

import java.util.UUID

class FileInventoryRepoLive extends Repo[FileInventory, FileInventory, UUID] with FileInventoryRepo:
  val fi = FileInventory.Table

  def findPathsBySourceSystem(sourceSystem: String)(using DbCon): Set[String] =
    sql"SELECT file_path FROM $fi WHERE source_system = $sourceSystem"
      .query[String]
      .run()
      .toSet
