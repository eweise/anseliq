package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.DisputeComment

trait DisputeCommentRepo extends DomainRepo[DisputeComment, DisputeComment]:
  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]
