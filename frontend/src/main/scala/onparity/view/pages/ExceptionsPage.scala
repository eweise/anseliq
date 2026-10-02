package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{DisputePayload, DisputeStatusPayload, DisputeTargetPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
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

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(ds => state.update(_.copy(disputes = ds))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(PageHeader.title("Exceptions")),
      div(
        cls := "w-full px-8",
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
    case DisputeTargetPayload.MovementSpec   => "Movement Spec"
    case DisputeTargetPayload.FileFeed       => "File Feed"

  private def renderRow(d: DisputePayload): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Neutral),
      Pill(targetLabel(d.targetType), Pill.Tone.Neutral),
      span(d.accountName),
      span(d.referenceLabel),
      a(
        href := s"/components/exceptions/${d.id}",
        cls  := "flex items-center gap-2 hover:underline cursor-pointer",
        onClick --> { e =>
          e.preventDefault()
          Nav.toExceptionDetail(d.id)
        },
        d.detail
          .map(text => span(cls := "truncate max-w-xs text-md-on-surface dark:text-gray-200", text))
          .getOrElse(div()),
        span(
          cls := "text-md-primary dark:text-md-primary-dark text-xs font-medium whitespace-nowrap",
          "View →"
        )
      )
    )
