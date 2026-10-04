package anseliq.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import anseliq.common.model.model.ID
import anseliq.domain.model.User
import anseliq.domain.port.driven.{Txn, UserRepo}

import java.util.UUID

// Callers are expected to normalize (lowercase) the email before insert/lookup — findByEmail does
// an exact match, no case-folding at the SQL level, to keep this consistent with the unique
// constraint on recon.app_user.email (also a plain, case-sensitive text column).
class UserRepoLive extends Repo[User, User, UUID] with UserRepo:
  private val u = User.Table

  override def findByEmail(email: String): Txn[Option[User]] =
    sql"""
        SELECT ${u.all}
        FROM $u AS u
        WHERE email = $email
      """.query[User].run().headOption

  override def findById(id: ID)(using DbCon): Option[User] =
    sql"""
        SELECT ${u.all}
        FROM $u AS u
        WHERE id = $id
      """.query[User].run().headOption
