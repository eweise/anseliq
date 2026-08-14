package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum EventStatus derives DbCodec:
  case Pending, InProgress, Consumed, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class EventState(
  @Id id:        ID                     = createID(),
  eventType:     String,
  payload:       String,
  status:        EventStatus            = EventStatus.Pending,
  retryCount:    Int                    = 0,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec:

  def event[T: _root_.upickle.default.Reader]: T = upickle.read(payload)

object EventState:
  def newEventState(event: Event): EventState =
    val now = OffsetDateTime.now()
    EventState(
      id        = event.id,
      eventType = event.eventType,
      payload   = upickle.write(event),
      createdAt = Some(now),
      updatedAt = Some(now)
    )

  val Table = TableInfo[EventState, EventState, UUID]
