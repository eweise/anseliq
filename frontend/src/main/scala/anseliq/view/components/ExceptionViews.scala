package anseliq.view.components

import anseliq.common.model.{ExceptionPayload, ExceptionStatusPayload, ExceptionTargetPayload}
import anseliq.routing.Nav
import com.raquo.laminar.api.L.*

// How exceptions are shown wherever they appear: the list on the Exceptions page and the dashboard,
// the open ones on a movement or reconciliation, and the colour of each status.
object ExceptionViews:

  def table(exceptions: Seq[ExceptionPayload]): HtmlElement =
    Table.create(Seq("Status", "Category", "Type", "Account", "Reference", "Detail"), exceptions.map(row))

  def statusTone(status: ExceptionStatusPayload): Pill.Tone = status match
    case ExceptionStatusPayload.New           => Pill.Tone.Accent
    case ExceptionStatusPayload.Investigating => Pill.Tone.Warning
    case ExceptionStatusPayload.Escalated     => Pill.Tone.Critical
    case ExceptionStatusPayload.Resolved      => Pill.Tone.Good

  def openCard(exceptions: Seq[ExceptionPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-3",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Open exceptions"),
      exceptions.map { exception =>
        div(
          cls := "flex flex-wrap items-center gap-3 text-sm",
          span(cls := "font-medium text-md-on-surface dark:text-white", exception.category.toString),
          Pill(exception.status.toString, statusTone(exception.status)),
          span(cls := Form.mutedTextCls, exception.assignedToName.fold("Unassigned")(n => s"Assigned to $n")),
          Buttons.textButton("Open thread", Observer[Unit](_ => Nav.toExceptionDetail(exception.id)))
        )
      }
    )

  private def targetLabel(targetType: ExceptionTargetPayload): String = targetType match
    case ExceptionTargetPayload.Movement       => "Movement"
    case ExceptionTargetPayload.Reconciliation => "Reconciliation"
    case ExceptionTargetPayload.MovementSpec   => "Movement Spec"
    case ExceptionTargetPayload.FileFeed       => "File Feed"

  private def row(d: ExceptionPayload): Seq[HtmlElement] =
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
