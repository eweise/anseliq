package onparity.domain.port.driven

import onparity.domain.model.EventState

trait EventRepo extends DomainRepo[EventState, EventState]:
  def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]]
  def findByEventType(eventType: String): Txn[Vector[EventState]]
