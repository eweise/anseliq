package perfectpair.domain.model

import perfectpair.common.model.model.ID

class ReconError(msg: String) extends RuntimeException(msg)

class NonRetryableError(msg: String) extends ReconError(msg)

class NotFoundError(val msg: String) extends NonRetryableError(msg)

class IllegalStateError(val msg: String) extends NonRetryableError(msg)

class DBError(msg: String) extends ReconError(msg)

extension [A](maybeEntity: Option[A])
  def ErrIfNotFound(name: String, id: ID): A =
    maybeEntity.getOrElse(throw NotFoundError(s"name with id $id not found"))
