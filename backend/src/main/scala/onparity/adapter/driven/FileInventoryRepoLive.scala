package onparity.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import onparity.common.model.model.ID
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

  def findAllWithMovementSpecName()(using DbCon): Vector[(FileInventory, String)] =
    sql"""
      SELECT fi.*, ms.name
      FROM $fi AS fi
      JOIN movement_spec ms ON fi.movement_spec_id = ms.id
      ORDER BY fi.last_modified_at DESC
    """.query[(FileInventory, String)].run()

  def findLatestByMovementSpecId(movementSpecId: ID)(using DbCon): Option[FileInventory] =
    sql"""
      SELECT ${fi.all}
      FROM $fi
      WHERE movement_spec_id = $movementSpecId
      ORDER BY last_modified_at DESC
      LIMIT 1
    """.query[FileInventory].run().headOption
