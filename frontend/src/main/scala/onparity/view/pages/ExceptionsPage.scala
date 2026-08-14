package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{DisputePayload, DisputeStatusPayload, DisputeTargetPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(disputes: Seq[DisputePayload] = Seq.empty)

  def apply(): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[Seq[DisputePayload]](HttpPath.url(HttpPath.disputes), SessionStore.token)

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(ds) => state.update(_.copy(disputes = ds))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-7xl",
        PageHeader.title("Exceptions & Disputes"),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Status", "Category", "Type", "Account", "Reference", "Detail"),
            s.disputes.map(renderRow)
          )
        }
      )
    )

  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  private def targetLabel(targetType: DisputeTargetPayload): String = targetType match
    case DisputeTargetPayload.Movement       => "Movement"
    case DisputeTargetPayload.Reconciliation => "Reconciliation"

  private def renderRow(d: DisputePayload): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Accent),
      Pill(targetLabel(d.targetType), Pill.Tone.Neutral),
      span(d.accountName),
      span(d.referenceLabel),
      a(
        href := s"/components/exceptions/${d.id}",
        cls  := "text-md-primary hover:underline cursor-pointer",
        onClick --> { e =>
          e.preventDefault()
          Nav.toExceptionDetail(d.id)
        },
        d.detail.getOrElse("View")
      )
    )
