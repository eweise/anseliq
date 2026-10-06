package anseliq.domain.service

import anseliq.domain.model.{Event, EventState, EventStatus, NonRetryableError}
import anseliq.domain.port.driven.{EventRepo, Txn}
import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.scalalogging.StrictLogging
import ox.scheduling.{Schedule, repeat}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class EventStore(eventRepo: EventRepo, clock: ClockService, dataSource: DataSource) extends StrictLogging:

  def publishEvent(event: Event): Txn[Unit] =
    val eventState = EventState.newEventState(event)
    eventRepo.insert(eventState)

  def consumeEvent[T <: Event](eventClass: Class[T], pollingSeconds: Int, maxRetries: Int)(
    consumer: T => Txn[Unit]
  ): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      try
        given transactor: Transactor = Transactor(dataSource)
        transact(transactor) {
          processNextEvent(eventClass, maxRetries)(consumer)
        }
      catch
        case ex: Exception =>
          logger.error(s"Error consuming ${eventClass.getSimpleName} event, will retry in ${pollingSeconds}s", ex)
    }

  def processNextEvent[T <: Event](eventClass: Class[T], maxRetries: Int)(consumer: T => Txn[Unit]): Txn[Unit] =
    // No separate InProgress marker: the SELECT above takes FOR UPDATE SKIP LOCKED, and this whole
    // method runs inside one transaction (see consumeEvent), so the row is already exclusively held
    // for the duration of processing, and no other connection can observe an intermediate status
    // anyway under READ COMMITTED. An InProgress write here would be a same-transaction no-op.
    eventRepo.getNextPendingEvent(eventClass.getSimpleName, maxRetries).foreach { eventState =>
      try {
        val event: T = eventClass.cast(upickle.read[Event](eventState.payload))
        consumer(event)
        eventRepo.update(eventState.copy(status = EventStatus.Consumed, updatedAt = Some(clock.now)))
      } catch {
        case ex: NonRetryableError =>
          eventRepo.update(
            eventState.copy(
              status       = EventStatus.Error,
              errorMessage = Some(ex.getMessage),
              updatedAt    = Some(clock.now)
            )
          )
        case ex: Exception =>
          if eventState.retryCount + 1 >= maxRetries then
            eventRepo.update(
              eventState.copy(
                status       = EventStatus.Error,
                errorMessage = Some(ex.getMessage),
                updatedAt    = Some(clock.now)
              )
            )
          else
            eventRepo.update(
              eventState.copy(
                status     = EventStatus.Pending,
                retryCount = eventState.retryCount + 1,
                updatedAt  = Some(clock.now)
              )
            )
      }
    }
