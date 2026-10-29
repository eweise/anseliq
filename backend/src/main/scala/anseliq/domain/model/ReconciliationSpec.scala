package anseliq.domain.model

import anseliq.common.model.Rule
import anseliq.common.model.model.ID
import anseliq.domain.model.Relationship.OneToOne
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum Relationship derives DbCodec:
  case OneToOne, OneToMany, ManyToMany

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ReconciliationSpec(
  @Id id:            ID                     = createId(),
  @Id accountId:     ID,
  agingLimit:        Int                    = 0,
  relationship:      Relationship           = OneToOne,
  inMovementSpecId:  ID,
  outMovementSpecId: ID,
  ruleJson:          String                 = "{}",
  var createdAt:     Option[OffsetDateTime] = None,
  var updatedAt:     Option[OffsetDateTime] = None
) extends Audited
    derives DbCodec:
  def rule(): Rule = upickle.read(ruleJson)

object ReconciliationSpec:
  val Table = TableInfo[ReconciliationSpec, ReconciliationSpec, UUID]
