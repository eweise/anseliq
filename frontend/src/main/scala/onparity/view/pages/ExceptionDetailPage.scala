package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{
  AddDisputeCommentRequest,
  DisputeCommentPayload,
  DisputeDetailPayload,
  DisputeStatusPayload,
  MovementPayload,
  ReconciliationPayload,
  UserPayload
}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, Card, Form, PageHeader, Pill}
import upickle.default.*

object ExceptionDetailPage:

  case class State(detail: Option[DisputeDetailPayload] = None, assignableUsers: Seq[UserPayload] = Seq.empty)

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

  // Mirrors the DisputeService.addComment rule server-side: a body is required for a plain comment
  // (no status change, no reassignment) and for a Resolved transition, so we can reject a bad
  // submission before it ever reaches the backend rather than surfacing a raw 500/stack trace.
  private def bodyRequired(newStatus: Option[DisputeStatusPayload], newAssignee: Option[String]): Boolean =
    (newStatus, newAssignee) match
      case (None, None)                        => true
      case (Some(DisputeStatusPayload.Resolved), _) => true
      case _                                    => false

  def apply(id: String): HtmlElement =
    val state           = Var(State())
    val loadBus         = EventBus[Unit]()
    val submitBus       = EventBus[(Option[DisputeStatusPayload], Option[String], Option[String])]()
    val assignBus       = EventBus[Unit]()
    val statusVar       = Var("")
    val assigneeVar     = Var("")
    val bodyVar         = Var("")
    val validationError = Var(Option.empty[String])

    def load() =
      ApiClient.get[DisputeDetailPayload](HttpPath.url(s"${HttpPath.dispute}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      ApiClient
        .get[Seq[UserPayload]](HttpPath.url(HttpPath.assignableUsers), SessionStore.token)
        .apiBind(users => state.update(_.copy(assignableUsers = users))),
      submitBus.events.flatMapSwitch { (newStatus, newAssignee, body) =>
        ApiClient.post[DisputeDetailPayload, AddDisputeCommentRequest](
          HttpPath.url(s"${HttpPath.dispute}/$id/comments"),
          AddDisputeCommentRequest(body, newStatus, newAssignee),
          SessionStore.token
        )
      }.apiBind { d =>
        state.update(_.copy(detail = Some(d)))
        statusVar.set("")
        assigneeVar.set("")
        bodyVar.set("")
        validationError.set(None)
      },
      assignBus.events
        .flatMapSwitch(_ =>
          ApiClient.postEmpty[DisputeDetailPayload](HttpPath.url(s"${HttpPath.dispute}/$id/assign"), SessionStore.token)
        )
        .apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.backLink(Observer[Unit](_ => Nav.push("/components/exceptions"))),
      child <-- state.signal.map {
        case State(None, _) => div()
        case State(Some(d), assignableUsers) =>
          div(
            PageHeader.title(s"Dispute — ${d.dispute.category.toString}"),
            div(
              cls := "flex gap-2 mb-4",
              Pill(d.dispute.status.toString, statusTone(d.dispute.status)),
              Pill(d.dispute.targetType.toString, Pill.Tone.Neutral)
            ),
            assignmentDetails(d, assignBus),
            targetDetails(d),
            h3(cls := "font-medium mt-6 mb-2 text-md-on-surface dark:text-white", "Thread"),
            div(cls := "flex flex-col gap-3 mb-6").amend(d.comments.map(commentEntry)*),
            commentForm(statusVar, assigneeVar, bodyVar, validationError, assignableUsers, submitBus)
          )
      }
    )

  private def assignmentDetails(d: DisputeDetailPayload, assignBus: EventBus[Unit]): HtmlElement =
    val myUserId    = SessionStore.currentUserNow.map(_.userId)
    val alreadyMine = myUserId.isDefined && d.dispute.assignedToUserId == myUserId
    div(
      cls := "flex items-center gap-4 mb-4 text-sm text-md-on-surface-variant dark:text-gray-400",
      span(s"Raised by: ${d.dispute.raisedByName.getOrElse("System")}"),
      span(s"Assigned to: ${d.dispute.assignedToName.getOrElse("Unassigned")}"),
      if alreadyMine then div()
      else Buttons.textButton("Assign to me", Observer[Unit](_ => assignBus.emit(())))
    )

  private def targetDetails(d: DisputeDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-4",
      div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-2", s"Account: ${d.dispute.accountName}"),
      d.dispute.detail
        .map(det => div(cls := "text-sm mb-2 text-md-on-surface dark:text-gray-200", s"Raised with: $det"))
        .getOrElse(div()),
      (d.movement, d.reconciliation) match
        case (Some(m), _) => movementDetails(m)
        case (_, Some(r)) => reconciliationDetails(r)
        case _            => div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "Target entity not found")
    )

  private def movementDetails(m: MovementPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1 text-md-on-surface dark:text-gray-200",
      div(s"Reference ID: ${m.referenceId}"),
      div(s"Amount: ${m.amount}"),
      div(s"Primary Account: ${m.inAccountId}"),
      div(s"Secondary Account: ${m.outAccountId}")
    )

  private def reconciliationDetails(r: ReconciliationPayload): HtmlElement =
    div(
      cls := "text-sm space-y-1 text-md-on-surface dark:text-gray-200",
      div(s"Description: ${r.description}"),
      div(s"Applied Rules: ${r.appliedRules.summary}"),
      div(s"Balance: ${r.balance}"),
      div(s"Status: ${r.status}"),
      div(s"Created: ${ViewHelper.formatDateTime(r.createdAt)}"),
      div(s"Updated: ${ViewHelper.formatDateTime(r.updatedAt)}")
    )

  private def commentEntry(c: DisputeCommentPayload): HtmlElement =
    div(
      cls := "border-l-2 border-md-outline pl-3",
      c.newStatus.map(s => Pill(s"→ ${s.toString}", statusTone(s))).getOrElse(div()),
      c.newAssignedToName
        .map(name => Pill(s"→ Assigned to $name", Pill.Tone.Accent))
        .getOrElse(div()),
      c.body.map(b => div(cls := "text-sm mt-1 text-md-on-surface dark:text-gray-200", b)).getOrElse(div()),
      div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-1", ViewHelper.formatDateTime(c.createdAt))
    )

  private def commentForm(
    statusVar:       Var[String],
    assigneeVar:     Var[String],
    bodyVar:         Var[String],
    validationError: Var[Option[String]],
    assignableUsers: Seq[UserPayload],
    submitBus:       EventBus[(Option[DisputeStatusPayload], Option[String], Option[String])]
  ): HtmlElement =
    val assigneeOptions =
      ("", "No change") +: assignableUsers.map(u => u.id.getOrElse("") -> s"${u.firstName} ${u.lastName}")
    div(
      cls := "border-t border-md-outline pt-4",
      Form.selectField(
        "newStatus",
        "Change status",
        statusOptions,
        "",
        v =>
          statusVar.set(v)
          validationError.set(None)
      ),
      Form.selectField(
        "newAssignee",
        "Assign to",
        assigneeOptions,
        "",
        v =>
          assigneeVar.set(v)
          validationError.set(None)
      ),
      div(
        cls := "mb-1",
        input(
          typ         := "text",
          placeholder := "Add a comment",
          cls         := "w-full px-4 py-3.5 border border-md-outline rounded text-md-on-surface dark:text-white bg-transparent",
          onInput.mapToValue --> Observer[String] { v =>
            bodyVar.set(v)
            validationError.set(None)
          }
        )
      ),
      child.maybe <-- validationError.signal.map(
        _.map(msg => div(cls := "text-sm text-red-600 dark:text-red-400 mb-3", msg))
      ),
      Buttons.linkButton(
        "Post",
        Observer[Unit](_ =>
          val newStatus   = DisputeStatusPayload.values.find(_.toString == statusVar.now())
          val newAssignee = Some(assigneeVar.now()).filter(_.nonEmpty)
          val body        = Some(bodyVar.now()).filter(_.nonEmpty)
          if bodyRequired(newStatus, newAssignee) && body.isEmpty then
            validationError.set(Some("A comment is required for this change."))
          else submitBus.emit((newStatus, newAssignee, body))
        )
      )
    )
