package anseliq.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import anseliq.common.model.model.ID
import anseliq.domain.model.FileInventory
import anseliq.domain.port.driven.FileInventoryRepo

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

  def findAllWithFileFeedName()(using DbCon): Vector[(FileInventory, String)] =
    sql"""
      SELECT ${fi.alias("fi").all}, ff.name
      FROM $fi AS fi
      JOIN file_feed ff ON fi.file_feed_id = ff.id
      ORDER BY fi.last_modified_at DESC
    """.query[(FileInventory, String)].run()

  def findLatestByFileFeedId(fileFeedId: ID)(using DbCon): Option[FileInventory] =
    sql"""
      SELECT ${fi.all}
      FROM $fi
      WHERE file_feed_id = $fileFeedId
      ORDER BY last_modified_at DESC
      LIMIT 1
    """.query[FileInventory].run().headOption
