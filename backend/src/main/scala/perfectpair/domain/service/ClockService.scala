package perfectpair.domain.service

import java.time.OffsetDateTime

class ClockService:
  @volatile private var currentTime: OffsetDateTime = OffsetDateTime.now()

  def now: OffsetDateTime = synchronized { currentTime }

  def addHours(hours: Long): Unit = synchronized { currentTime = currentTime.plusHours(hours) }
