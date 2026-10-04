package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.DisputeComment

trait DisputeCommentRepo extends DomainRepo[DisputeComment, DisputeComment]:
  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]
