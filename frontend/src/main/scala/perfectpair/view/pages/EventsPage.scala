package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.*
import perfectpair.http.ApiClient
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Form, Table}
import upickle.default.*

object EventsPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[String]()

    div(
      onMountCallback(_ => fetchBus.emit(state.now().selectedEventType)),
      fetchBus.events.flatMapSwitch { eventType =>
        ApiClient.get[Seq[EventPayload]](
          HttpPath.url(s"${HttpPath.events}/$eventType"),
          SessionStore.token
        )
      } --> {
        case Right(events) => state.update(_.copy(events = events))
        case Left(e)       => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full",
      div(
        cls := "w-full max-w-5xl",
        h2(cls := "text-2xl font-medium mb-6", "Events"),
        div(
          cls := "mb-6 max-w-xs",
          Form.selectField(
            "event-type-select",
            "Event Type",
            eventTypeOptions,
            state.now().selectedEventType,
            v =>
              state.update(s => s.copy(selectedEventType = v, events = Seq.empty))
              fetchBus.emit(v)
          )
        ),
        child <-- state.signal.map { s =>
          val extraHeader = s.selectedEventType match
            case "MoneyMovementsIngested" => "Movement Spec ID"
            case "AccountReconciled"      => "Account ID"
            case "NewFileDetected"        => "File Inventory ID"
            case _                        => "ID"
          val headers = Seq("Status", "Retries", "Error", "Created At", extraHeader)
          val rows = s.events.map { event =>
            Seq(
              span(event.status),
              span(event.retryCount.toString),
              span(event.errorMessage.getOrElse("-")),
              span(event.createdAt.map(ViewHelper.formatDateTime).getOrElse("-"))
            ) ++ extraCells(event)
          }
          Table.create(headers, rows)
        }
      )
    )

  private val eventTypeOptions: Seq[(String, String)] = Seq(
    "MoneyMovementsIngested" -> "MoneyMovementsIngested",
    "AccountReconciled"      -> "AccountReconciled",
    "NewFileDetected"        -> "NewFileDetected"
  )

  private def extraCells(event: EventPayload): Seq[HtmlElement] = event match
    case e: MoneyMovementsIngestedPayload => Seq(span(e.movementSpecID))
    case e: AccountReconciledPayload      => Seq(span(e.accountID))
    case e: NewFileDetectedPayload        => Seq(span(e.fileInventoryID))

  case class State(
    selectedEventType: String            = "MoneyMovementsIngested",
    events:            Seq[EventPayload] = Seq.empty
  )
