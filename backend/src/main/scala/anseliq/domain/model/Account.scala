package anseliq.domain.model

import anseliq.common.model.model.{ID, Money}
import anseliq.domain.model.helper.JSONBProps
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util
import java.util.UUID

enum AccountType derives DbCodec:
  case Primary, Secondary, Recon

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
  @Id id:                  ID                     = createId(),
  name:                    String,
  ledgerId:                ID,
  accountType:             AccountType,
  properties:              JSONBProps             = JSONBProps(Map()),
  var balance:             Money                  = 0,
  var reconciledBalance:   Money                  = 0,
  var unreconciledBalance: Money                  = 0,
  var createdAt:           Option[OffsetDateTime] = None,
  var updatedAt:           Option[OffsetDateTime] = None
) extends Audited
    derives DbCodec

object Account:
  val Table = TableInfo[Account, Account, UUID]
