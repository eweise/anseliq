package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.Rule
import onparity.common.model.model.ID
import onparity.domain.model.Relationship.OneToOne

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum Relationship derives DbCodec:
  case OneToOne, OneToMany, ManyToMany

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ReconciliationSpec(
  @Id id:            ID                     = createID(),
  @Id accountId:     ID,
  agingLimit:        Int                    = 0,
  relationship:      Relationship           = OneToOne,
  inMovementSpecId:  ID,
  outMovementSpecId: ID,
  ruleJson:          String                 = "{}",
  var createdAt:     Option[OffsetDateTime] = None,
  var updatedAt:     Option[OffsetDateTime] = None
) derives DbCodec:
  def rule(): Rule = upickle.read(ruleJson)

object ReconciliationSpec:
  val Table = TableInfo[ReconciliationSpec, ReconciliationSpec, UUID]
