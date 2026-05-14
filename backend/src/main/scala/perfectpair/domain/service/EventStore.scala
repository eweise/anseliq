package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import ox.scheduling.{Schedule, repeat}
import perfectpair.domain.model.{Event, EventStatus, RetryableException}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class EventStore(using eventRepo: EventRepo, clock: ClockService, dataSource: DataSource):

  def publishEvent(event: Event): Txn[Unit] =
    eventRepo.insert(event)

  def processNextEvent(maxRetries: Int)(consumer: Event => Unit): Txn[Unit] =
    eventRepo.getNextPendingEvent(maxRetries).foreach { event =>
      val inProgress = event.copy(status = EventStatus.InProgress, updatedAt = Some(clock.now))
      eventRepo.update(inProgress)
      try {
        consumer(inProgress)
        eventRepo.update(inProgress.copy(status = EventStatus.Consumed, updatedAt = Some(clock.now)))
      } catch {
        case ex: RetryableException =>
          if inProgress.retryCount + 1 >= maxRetries then
            eventRepo.update(inProgress.copy(
              status       = EventStatus.Error,
              errorMessage = Some(ex.getMessage),
              updatedAt    = Some(clock.now)
            ))
          else
            eventRepo.update(inProgress.copy(
              status     = EventStatus.Pending,
              retryCount = inProgress.retryCount + 1,
              updatedAt  = Some(clock.now)
            ))
        case ex: Exception =>
          eventRepo.update(inProgress.copy(
            status       = EventStatus.Error,
            errorMessage = Some(ex.getMessage),
            updatedAt    = Some(clock.now)
          ))
      }
    }

  def receiveEvent(pollingSeconds: Int, maxRetries: Int)(consumer: Event => Unit): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      given transactor: Transactor = Transactor(dataSource)
      transact(transactor) {
        processNextEvent(maxRetries)(consumer)
      }
    }
