package anseliq.domain.model

import anseliq.common.model.DataSourceConfig
import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

// Owns "where a file comes from and how to parse it" — the part of ingestion shared across every
// account a file touches. One row per physical file source; one or more MovementSpecs route off
// the same feed, each claiming the rows belonging to one account (via routingFilter).
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FileFeed(
  @Id id:               ID          = createID(),
  name:                 String      = "",
  dataSourceJson:       String      = "",
  expectedCadenceHours: Option[Int] = None,
  // None means no cutoff is enforced, same "unset = feature disabled" convention as
  // expectedCadenceHours. See Ingestor.ingestResumable for where this stops ingestion.
  maxParseErrors: Option[Int]            = None,
  var createdAt:  Option[OffsetDateTime] = None,
  var updatedAt:  Option[OffsetDateTime] = None
) derives DbCodec:
  def dataSourceConfig(): DataSourceConfig = upickle.read(dataSourceJson)

object FileFeed:
  val Table = TableInfo[FileFeed, FileFeed, UUID]
