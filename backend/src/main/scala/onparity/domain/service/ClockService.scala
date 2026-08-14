package onparity.domain.service

import java.time.OffsetDateTime

trait ClockService:
  @volatile private var currentTime: OffsetDateTime = OffsetDateTime.now()

  def now: OffsetDateTime = synchronized(currentTime)

  def addHours(hours: Long): Unit = synchronized { currentTime = currentTime.plusHours(hours) }

class ClockServiceLive extends ClockService
