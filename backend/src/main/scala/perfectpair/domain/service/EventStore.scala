package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import ox.scheduling.{Schedule, repeat}
import perfectpair.domain.model.{Event, EventState, EventStatus, NonRetryableError}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class EventStore(using eventRepo: EventRepo, clock: ClockService, dataSource: DataSource):

  def publishEvent(event: Event): Txn[Unit] =
    val eventState = EventState.newEventState(event)
    eventRepo.insert(eventState)

  def consumeEvent(eventType: String, pollingSeconds: Int, maxRetries: Int)(consumer: Event => Unit): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      given transactor: Transactor = Transactor(dataSource)
      transact(transactor) {
        processNextEvent(eventType, maxRetries)(consumer)
      }
    }

  def processNextEvent(eventType: String, maxRetries: Int)(consumer: Event => Unit): Txn[Unit] =
    eventRepo.getNextPendingEvent(eventType, maxRetries).foreach { eventState =>
      val inProgress = eventState.copy(status = EventStatus.InProgress, updatedAt = Some(clock.now))
      eventRepo.update(inProgress)
      try {
        val event: Event = upickle.read(inProgress.payload)
        consumer(event)
        eventRepo.update(inProgress.copy(status = EventStatus.Consumed, updatedAt = Some(clock.now)))
      } catch {
        case ex: NonRetryableError =>
          eventRepo.update(
            inProgress.copy(
              status       = EventStatus.Error,
              errorMessage = Some(ex.getMessage),
              updatedAt    = Some(clock.now)
            )
          )
        case ex: Exception =>
          if inProgress.retryCount + 1 >= maxRetries then
            eventRepo.update(
              inProgress.copy(
                status       = EventStatus.Error,
                errorMessage = Some(ex.getMessage),
                updatedAt    = Some(clock.now)
              )
            )
          else
            eventRepo.update(
              inProgress.copy(
                status     = EventStatus.Pending,
                retryCount = inProgress.retryCount + 1,
                updatedAt  = Some(clock.now)
              )
            )
      }
    }
