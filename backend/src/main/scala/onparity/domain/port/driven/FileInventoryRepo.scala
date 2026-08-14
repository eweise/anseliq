package onparity.domain.port.driven

import com.augustnagro.magnum.DbCon
import onparity.domain.model.FileInventory

trait FileInventoryRepo extends DomainRepo[FileInventory, FileInventory]:
  def findBySourceSystem(sourceSystem: String)(using DbCon): Set[FileInventory]
