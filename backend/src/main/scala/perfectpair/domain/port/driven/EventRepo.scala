package perfectpair.domain.port.driven

import perfectpair.domain.model.EventState

trait EventRepo extends DomainRepo[EventState, EventState]:
  def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]]
