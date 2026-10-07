package anseliq.adapter.driven

import anseliq.domain.model.EventState
import anseliq.domain.port.driven.{EventRepo, Txn}
import com.augustnagro.magnum.{Repo, sql}

import java.util.UUID

class EventRepoLive extends Repo[EventState, EventState, UUID] with EventRepo:
  val e = EventState.Table

  override def findByEventType(eventType: String): Txn[Vector[EventState]] =
    sql"SELECT ${e.all} FROM $e WHERE event_type = $eventType".query[EventState].run()
