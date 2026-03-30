package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.Rule
import perfectpair.common.model.model.ID
import perfectpair.domain.model.Relationship.OneToOne

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum Relationship derives DbCodec:
  case OneToOne, OneToMany, ManyToOne, ManyToMany

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ReconciliationSpec(
  @Id id:        ID           = createID(),
  @Id accountId: ID,
  description:   String       = "",
  agingLimit:    Int          = 0,
  relationship:  Relationship = OneToOne,
  ruleJson:      String       = "{}"
) extends Auditable
    derives DbCodec:
  def rule(): Rule = upickle.read(ruleJson)

object ReconciliationSpec:
  val Table = TableInfo[ReconciliationSpec, ReconciliationSpec, UUID]
