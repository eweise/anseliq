package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.DisputeComment

trait DisputeCommentRepo extends DomainRepo[DisputeComment, DisputeComment]:
  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]
