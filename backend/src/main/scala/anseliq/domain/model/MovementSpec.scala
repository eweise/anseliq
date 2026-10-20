package anseliq.domain.model

import anseliq.common.model.RoutingFilter
import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MovementSpec(
  @Id id:       ID     = createID(),
  name:         String = "",
  inAccountId:  ID,
  outAccountId: ID,
  fileFeedId:   ID,
  // None means this spec claims every row from its feed — the default for a feed with only one
  // account (a CSV already scoped to one account). Some(filter) is the multi-account case: several
  // specs share one FileFeed, each claiming the rows whose parsed propertyField matches matchValue.
  routingFilterJson: Option[String]         = None,
  var createdAt:     Option[OffsetDateTime] = None,
  var updatedAt:     Option[OffsetDateTime] = None
) extends Audited derives DbCodec:
  def routingFilter(): Option[RoutingFilter] = routingFilterJson.map(upickle.read[RoutingFilter](_))

object MovementSpec:
  val Table = TableInfo[MovementSpec, MovementSpec, UUID]
