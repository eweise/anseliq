package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.domain.model.{EventState, EventStatus}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import java.util.UUID

class EventRepoLive extends Repo[EventState, EventState, UUID] with EventRepo:
  val e = EventState.Table

  override def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]] =
    val found = sql"""
      SELECT ${e.all}
      FROM $e
      WHERE status in(${EventStatus.Pending}, ${EventStatus.Error})
      AND event_type = $eventType
      AND retry_count < $maxRetries
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
    """.query[EventState].run()
    found.headOption
