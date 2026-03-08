package perfectpair.domain.model

class ReconError extends RuntimeException

class NonRetryableError extends ReconError

class NotFoundError(val msg: String) extends NonRetryableError

class DBError extends ReconError
