package anseliq.domain.port.driven

import anseliq.domain.model.EventState

trait EventRepo extends DomainRepo[EventState, EventState]:
  def findByEventType(eventType: String): Txn[Vector[EventState]]
