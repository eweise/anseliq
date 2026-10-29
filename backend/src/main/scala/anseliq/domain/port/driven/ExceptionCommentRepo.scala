package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.ExceptionComment

trait ExceptionCommentRepo extends DomainRepo[ExceptionComment, ExceptionComment]:
  def findByExceptionId(exceptionId: ID): Txn[Vector[ExceptionComment]]
