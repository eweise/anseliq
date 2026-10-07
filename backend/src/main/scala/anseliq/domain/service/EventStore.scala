package anseliq.domain.service

import anseliq.domain.model.{Event, EventState}
import anseliq.domain.port.driven.{EventRepo, Txn}

// Records what happened, in the caller's transaction, for the activity feed. Nothing consumes
// events: work that should follow something is queued as a job (see JobManager).
class EventStore(eventRepo: EventRepo):

  def publishEvent(event: Event): Txn[Unit] =
    eventRepo.insert(EventState.newEventState(event))
