package onparity.domain.model

import com.augustnagro.magnum.*

import java.time.OffsetDateTime

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class User(
  @Id id:        Long,
  firstName:     Option[String],
  lastName:      String,
  var createdAt: Option[OffsetDateTime] = None,
  var updateAt:  Option[OffsetDateTime] = None
) derives DbCodec
