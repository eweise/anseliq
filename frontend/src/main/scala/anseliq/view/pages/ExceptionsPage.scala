package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{ExceptionPayload, ExceptionStatusPayload, ExceptionTargetPayload}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(exceptions: Seq[ExceptionPayload] = Seq.empty)

  def apply(): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[Seq[ExceptionPayload]](HttpPath.exceptions, SessionStore.token)

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(ds => state.update(_.copy(exceptions = ds))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(PageHeader.title("Exceptions")),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Status", "Category", "Type", "Account", "Reference", "Detail"),
            s.exceptions.map(renderRow)
          )
        }
      )
    )

  private def statusTone(status: ExceptionStatusPayload): Pill.Tone = status match
    case ExceptionStatusPayload.New           => Pill.Tone.Accent
    case ExceptionStatusPayload.Investigating => Pill.Tone.Warning
    case ExceptionStatusPayload.Escalated     => Pill.Tone.Critical
    case ExceptionStatusPayload.Resolved      => Pill.Tone.Good

  private def targetLabel(targetType: ExceptionTargetPayload): String = targetType match
    case ExceptionTargetPayload.Movement       => "Movement"
    case ExceptionTargetPayload.Reconciliation => "Reconciliation"
    case ExceptionTargetPayload.MovementSpec   => "Movement Spec"
    case ExceptionTargetPayload.FileFeed       => "File Feed"

  private def renderRow(d: ExceptionPayload): Seq[HtmlElement] =
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
