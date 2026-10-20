package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

// Fixed, global set of roles (not a user-editable business object) — Admin manages users/roles,
// Analyst does day-to-day reconciliation work, Viewer is read-only. Coarse-grained for now: every
// route requires some authenticated user, and only the Admin-only routes additionally check this.
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum UserRole derives DbCodec:
  case Admin, Analyst, Viewer

// Table is named app_user, not user: "user" is a reserved word in Postgres (collides with the
// CURRENT_USER function). @SqlName overrides Magnum's default class-name-derived table name
// (which would otherwise be "user") without needing to rename the Scala class itself. Unqualified
// (no "recon." prefix) to match every other repo in this app — DbDataSource sets currentSchema to
// "recon" on the connection, so unqualified table names already resolve within that schema.
@SqlName("app_user")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class User(
  @Id id:        ID                     = createID(),
  email:         String,
  passwordHash:  String,
  firstName:     String,
  lastName:      String,
  role:          UserRole,
  active:        Boolean                = true,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) extends Audited derives DbCodec

object User:
  val Table = TableInfo[User, User, UUID]
