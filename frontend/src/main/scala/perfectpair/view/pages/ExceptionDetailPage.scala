package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{AddDisputeCommentRequest, DisputeCommentPayload, DisputeDetailPayload, DisputeStatusPayload, MovementPayload, ReconciliationPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Form, PageHeader, Pill}
import upickle.default.*

object ExceptionDetailPage:

  case class State(detail: Option[DisputeDetailPayload] = None)

  private val statusOptions: Seq[(String, String)] =
    ("", "No change") +: Seq(
      DisputeStatusPayload.New,
      DisputeStatusPayload.Investigating,
      DisputeStatusPayload.Escalated,
      DisputeStatusPayload.Resolved
    ).map(s => s.toString -> s.toString)

  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  def apply(id: String): HtmlElement =
    val state       = Var(State())
    val loadBus     = EventBus[Unit]()
    val submitBus   = EventBus[(Option[DisputeStatusPayload], Option[String])]()
    val statusVar   = Var("")
    val bodyVar     = Var("")

    def load() =
      ApiClient.get[DisputeDetailPayload](HttpPath.url(s"${HttpPath.dispute}/$id"), SessionStore.token)

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(d) => state.update(_.copy(detail = Some(d)))
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      submitBus.events.flatMapSwitch { (newStatus, body) =>
        ApiClient.post[DisputeDetailPayload, AddDisputeCommentRequest](
          HttpPath.url(s"${HttpPath.dispute}/$id/comments"),
          AddDisputeCommentRequest(body, newStatus),
          SessionStore.token
        )
      } --> {
        case Right(d) =>
          state.update(_.copy(detail = Some(d)))
          statusVar.set("")
          bodyVar.set("")
        case Left(e) => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-4xl",
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.push("/components/exceptions"))),
        child <-- state.signal.map {
          case State(None) => div()
          case State(Some(d)) =>
            div(
              cls := "text-left",
              PageHeader.title(s"Dispute — ${d.dispute.category.toString}"),
              div(
                cls := "flex gap-2 mb-4",
                Pill(d.dispute.status.toString, statusTone(d.dispute.status)),
                Pill(d.dispute.targetType.toString, Pill.Tone.Neutral)
              ),
              targetDetails(d),
              h3(cls := "font-medium mt-6 mb-2", "Thread"),
              div(cls := "flex flex-col gap-3 mb-6").amend(d.comments.map(commentEntry)*),
              commentForm(statusVar, bodyVar, submitBus)
            )
        }
      )
    )

  private def targetDetails(d: DisputeDetailPayload): HtmlElement =
    div(
      cls := "border border-md-outline rounded-lg p-4 mb-4",
      div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-2", s"Account: ${d.dispute.accountName}"),
      d.dispute.detail.map(det => div(cls := "text-sm mb-2", s"Raised with: $det")).getOrElse(div()),
      (d.movement, d.reconciliation) match
        case (Some(m), _) => movementDetails(m)
        case (_, Some(r)) => reconciliationDetails(r)
        case _            => div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "Target entity not found")
    )

  private def movementDetails(m: MovementPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1",
      div(s"Reference ID: ${m.referenceId}"),
      div(s"Amount: ${m.amount}"),
      div(s"In Account: ${m.inAccountId}"),
      div(s"Out Account: ${m.outAccountId}")
    )

  private def reconciliationDetails(r: ReconciliationPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1",
      div(s"Description: ${r.description}"),
      div(s"Applied Rules: ${r.appliedRules}"),
      div(s"Balance: ${r.balance}"),
      div(s"Status: ${r.status}"),
      div(s"Created: ${ViewHelper.formatDateTime(r.createdAt)}"),
      div(s"Updated: ${ViewHelper.formatDateTime(r.updatedAt)}")
    )

  private def commentEntry(c: DisputeCommentPayload): HtmlElement =
    div(
      cls := "border-l-2 border-md-outline pl-3",
      c.newStatus.map(s => Pill(s"→ ${s.toString}", statusTone(s))).getOrElse(div()),
      c.body.map(b => div(cls := "text-sm mt-1", b)).getOrElse(div()),
      div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-1", ViewHelper.formatDateTime(c.createdAt))
    )

  private def commentForm(
    statusVar: Var[String],
    bodyVar:   Var[String],
    submitBus: EventBus[(Option[DisputeStatusPayload], Option[String])]
  ): HtmlElement =
    div(
      cls := "border-t border-md-outline pt-4",
      Form.selectField("newStatus", "Change status", statusOptions, "", v => statusVar.set(v)),
      div(
        cls := "mb-4",
        input(
          typ         := "text",
          placeholder := "Add a comment",
          cls         := "w-full px-4 py-3.5 border border-md-outline rounded text-md-on-surface dark:text-white bg-transparent",
          onInput.mapToValue --> Observer[String](v => bodyVar.set(v))
        )
      ),
      Buttons.linkButton(
        "Post",
        Observer[Unit](_ =>
          val newStatus = DisputeStatusPayload.values.find(_.toString == statusVar.now())
          val body      = Some(bodyVar.now()).filter(_.nonEmpty)
          submitBus.emit((newStatus, body))
        )
      )
    )
