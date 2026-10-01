package onparity.domain.model

import onparity.common.model.model.ID

class ReconError(msg: String, cause: Throwable) extends RuntimeException(msg, cause):
  def this(msg: String) = this(msg, null)

class NonRetryableError(msg: String, cause: Throwable) extends ReconError(msg, cause):
  def this(msg: String) = this(msg, null)

class NotFoundError(val msg: String) extends NonRetryableError(msg)

class IllegalStateError(val msg: String) extends NonRetryableError(msg)

class IllegalArgumentError(val argumentName: String, val msg: String)
    extends NonRetryableError(s"${argumentName}: $msg")

class UnauthorizedError(val msg: String) extends NonRetryableError(msg)

class ForbiddenError(val msg: String) extends NonRetryableError(msg)

class DBError(msg: String) extends ReconError(msg)

extension [A](maybeEntity: Option[A])
  def ErrIfNotFound(name: String, id: ID): A =
    maybeEntity.getOrElse(throw NotFoundError(s"$name with id $id not found"))
