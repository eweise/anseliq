package anseliq.domain.port.driven

import anseliq.domain.model.EventState

trait EventRepo extends DomainRepo[EventState, EventState]:
  def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]]
  def findByEventType(eventType:     String): Txn[Vector[EventState]]
