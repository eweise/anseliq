package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.domain.model.{Event, EventStatus}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import java.util.UUID

class EventRepoLive extends Repo[Event, Event, UUID] with EventRepo:
  val e = Event.Table

  override def getNextPendingEvent(maxRetries: Int): Txn[Option[Event]] =
    sql"""
      SELECT ${e.all}
      FROM $e
      WHERE status = ${EventStatus.Pending}
      AND retry_count < $maxRetries
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
    """.query[Event].run().headOption
