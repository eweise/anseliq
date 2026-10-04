package anseliq.domain.port.driven

import anseliq.domain.model.User

trait UserRepo extends DomainRepo[User, User]:
  def findByEmail(email: String): Txn[Option[User]]
