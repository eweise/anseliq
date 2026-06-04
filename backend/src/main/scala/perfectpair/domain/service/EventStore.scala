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

  def consumeEvent[T <: Event](eventClass: Class[T], pollingSeconds: Int, maxRetries: Int)(consumer: T => Txn[Unit]): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      try
        given transactor: Transactor = Transactor(dataSource)
        transact(transactor) {
          processNextEvent(eventClass, maxRetries)(consumer)
        }
      catch case ex: Exception =>
        println(s"Error consuming ${eventClass.getSimpleName} event, will retry in ${pollingSeconds}s: ${ex.getMessage}")
    }

  def processNextEvent[T <: Event](eventClass: Class[T], maxRetries: Int)(consumer: T => Txn[Unit]): Txn[Unit] =
    eventRepo.getNextPendingEvent(eventClass.getSimpleName, maxRetries).foreach { eventState =>
      val inProgress = eventState.copy(status = EventStatus.InProgress, updatedAt = Some(clock.now))
      eventRepo.update(inProgress)
      try {
        val event: T = eventClass.cast(upickle.read[Event](inProgress.payload))
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
