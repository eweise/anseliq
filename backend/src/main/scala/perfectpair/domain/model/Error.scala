package perfectpair.domain.model

import perfectpair.common.model.model.ID

class ReconError extends RuntimeException

class NonRetryableError extends ReconError

class NotFoundError(val msg: String) extends NonRetryableError

class IllegalStateError(val masg:String) extends NonRetryableError

extension [A](maybeEntity: Option[A])
  def ErrIfNotFound(name: String, id: ID): A =
    maybeEntity.getOrElse(throw NotFoundError(s"name with id $id not found"))

class DBError extends ReconError
