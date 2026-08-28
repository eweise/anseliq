package onparity.domain.port.driven

import onparity.domain.model.User

trait UserRepo extends DomainRepo[User, User]:
  def findByEmail(email: String): Txn[Option[User]]
