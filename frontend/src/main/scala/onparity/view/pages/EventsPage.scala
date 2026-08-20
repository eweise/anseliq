package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Form, Icons, PageHeader, Table}
import upickle.default.*

object EventsPage:

  private val eventTypeOptions: Seq[(String, String)] = Seq(
    "MoneyMovementsIngested" -> "MoneyMovementsIngested",
    "AccountReconciled"      -> "AccountReconciled",
    "NewFileDetected"        -> "NewFileDetected"
  )

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[String]()

    div(
      EventStream
        .merge(
          EventStream.fromValue(state.now().selectedEventType, emitOnce = true),
          fetchBus.events
        )
        .flatMapSwitch { eventType =>
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
        PageHeader.title("Events"),
        div(
          cls := "mb-6 flex items-end gap-3",
          div(
            cls := "max-w-xs",
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
          button(
            cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-10 h-10 rounded-full hover:bg-green-500",
            typ := "button",
            onClick --> (_ => fetchBus.emit(state.now().selectedEventType)),
            Icons.refresh
          )
        ),
        child <-- state.signal.map { s =>
          val extraHeaders = s.selectedEventType match
            case "MoneyMovementsIngested" =>
              Seq(
                "Movement Spec Name",
                "File Name",
                "Record Count",
                "Ingestion Time (s)",
                "Last Processed Line",
                "Movement Spec ID"
              )
            case "AccountReconciled" => Seq("Account ID")
            case "NewFileDetected"   => Seq("File Inventory ID")
            case _                   => Seq("ID")
          val headers = Seq("Status", "Retries", "Error", "Created At") ++ extraHeaders
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

  private def extraCells(event: EventPayload): Seq[HtmlElement] = event match
    case e: MoneyMovementsIngestedPayload =>
      Seq(
        span(e.movementSpecName),
        span(e.fileName),
        span(e.recordCount.toString),
        span(e.ingestionTimeSeconds.toString),
        span(e.lastProcessedLine.map(_.toString).getOrElse("-")),
        span(e.movementSpecID)
      )
    case e: AccountReconciledPayload => Seq(span(e.accountID))
    case e: NewFileDetectedPayload   => Seq(span(e.fileInventoryID))

  case class State(
    selectedEventType: String            = "MoneyMovementsIngested",
    events:            Seq[EventPayload] = Seq.empty
  )
