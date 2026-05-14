package perfectpair.domain.port.driven

import perfectpair.domain.model.Event

trait EventRepo extends DomainRepo[Event, Event]:
  def getNextPendingEvent(maxRetries: Int): Txn[Option[Event]]
