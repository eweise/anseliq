package anseliq.domain.service

import java.time.OffsetDateTime

trait ClockService:
  @volatile private var offsetHours = 0

  def now: OffsetDateTime = OffsetDateTime.now().plusHours(offsetHours)

  def addHours(hours: Int): Unit = synchronized { offsetHours = offsetHours + hours }

class ClockServiceLive extends ClockService
