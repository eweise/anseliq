package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum EventStatus derives DbCodec:
  case Pending, InProgress, Consumed, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Event(
  @Id id:        ID                     = createID(),
  eventType:     String,
  payload:       String,
  status:        EventStatus            = EventStatus.Pending,
  retryCount:    Int                    = 0,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Event:
  val Table = TableInfo[Event, Event, UUID]

class RetryableException(message: String) extends Exception(message)
