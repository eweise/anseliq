package anseliq.domain.port.driven

import com.augustnagro.magnum.DbCon
import anseliq.common.model.model.ID
import anseliq.domain.model.FileInventory

trait FileInventoryRepo extends DomainRepo[FileInventory, FileInventory]:
  def findBySourceSystem(sourceSystem: String)(using DbCon): Set[FileInventory]

  def findAllWithFileFeedName()(using DbCon): Vector[(FileInventory, String)]

  def findLatestByFileFeedId(fileFeedId: ID)(using DbCon): Option[FileInventory]
