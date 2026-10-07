package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class EventState(
  @Id id:    ID             = createID(),
  eventType: String,
  payload:   String,
  createdAt: OffsetDateTime = OffsetDateTime.now()
) derives DbCodec:

  def event[T: _root_.upickle.default.Reader]: T = upickle.read(payload)

object EventState:
  def newEventState(event: Event): EventState =
    EventState(
      id        = event.id,
      eventType = event.eventType,
      payload   = upickle.write(event)
    )

  val Table = TableInfo[EventState, EventState, UUID]
