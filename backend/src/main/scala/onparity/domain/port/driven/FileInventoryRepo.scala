package onparity.domain.port.driven

import com.augustnagro.magnum.DbCon
import onparity.common.model.model.ID
import onparity.domain.model.FileInventory

trait FileInventoryRepo extends DomainRepo[FileInventory, FileInventory]:
  def findBySourceSystem(sourceSystem: String)(using DbCon): Set[FileInventory]

  def findAllWithMovementSpecName()(using DbCon): Vector[(FileInventory, String)]

  def findLatestByMovementSpecId(movementSpecId: ID)(using DbCon): Option[FileInventory]
