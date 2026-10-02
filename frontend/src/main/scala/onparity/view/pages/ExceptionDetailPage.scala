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
import onparity.view.components.{Buttons, Card, DetailList, Form, PageHeader, Pill, ReconStatus, ReconciliationActions}
import upickle.default.*

object ExceptionDetailPage:

  case class State(detail: Option[DisputeDetailPayload] = None, assignableUsers: Seq[UserPayload] = Seq.empty)

  // Resolved is left out for a dispute the system resolves itself (automaticResolution is set):
  // marking it resolved by hand wouldn't fix the underlying problem, and the backend rejects it.
  private def statusOptions(d: DisputeDetailPayload): Seq[(String, String)] =
    ("", "No change") +: Seq(
      DisputeStatusPayload.New,
      DisputeStatusPayload.Investigating,
      DisputeStatusPayload.Escalated,
      DisputeStatusPayload.Resolved
    ).filterNot(s => s == DisputeStatusPayload.Resolved && d.dispute.automaticResolution.isDefined)
      .map(s => s.toString -> s.toString)

  def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
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
      child <-- state.signal.map {
        case State(None, _) => div()
        case State(Some(d), assignableUsers) =>
          // A case page rather than another detail page: the workflow (thread, status, assignee)
          // is the main column, and what the dispute is about is a compact subject card beside
          // it, with the same actions as that object's own page and a link through to it.
          val title = s"${d.dispute.category} on ${d.dispute.referenceLabel}"
          div(
            cls := "relative flex flex-col gap-6",
            PageHeader.breadcrumb(
              "Exceptions" -> Some(Observer[Unit](_ => Nav.push("/components/exceptions"))),
              title        -> None
            ),
            div(
              cls := "flex flex-col gap-2",
              div(
                cls := "flex flex-wrap items-center gap-3",
                h2(cls := "text-2xl font-medium text-gray-900 dark:text-white", title),
                Pill(d.dispute.status.toString, statusTone(d.dispute.status))
              ),
              assignmentDetails(d, assignBus)
            ),
            div(
              cls := "grid grid-cols-1 lg:grid-cols-3 gap-6 items-start",
              div(
                cls := s"${Card.cls} p-5 lg:col-span-2 min-w-0 flex flex-col gap-4",
                h3(cls := "font-medium text-md-on-surface dark:text-white", "Thread"),
                if d.comments.isEmpty then p(cls := s"text-sm ${Form.mutedTextCls}", "No comments yet.")
                else div(cls := "flex flex-col gap-3").amend(d.comments.map(commentEntry)*),
                commentForm(d, statusVar, assigneeVar, bodyVar, validationError, assignableUsers, submitBus)
              ),
              subjectCard(d, Observer[Unit](_ => loadBus.emit(())))
            )
          )
      }
    )

  private def assignmentDetails(d: DisputeDetailPayload, assignBus: EventBus[Unit]): HtmlElement =
    val myUserId    = SessionStore.currentUserNow.map(_.userId)
    val alreadyMine = myUserId.isDefined && d.dispute.assignedToUserId == myUserId
    div(
      cls := "flex flex-wrap items-center gap-4 text-sm text-md-on-surface-variant dark:text-gray-400",
      span(s"Raised by: ${d.dispute.raisedByName.getOrElse("System")}"),
      span(s"Assigned to: ${d.dispute.assignedToName.getOrElse("Unassigned")}"),
      // A resolved dispute can't be assigned (the backend rejects it): there's no work left to own.
      if alreadyMine || d.dispute.status == DisputeStatusPayload.Resolved then div()
      else Buttons.textButton("Assign to me", Observer[Unit](_ => assignBus.emit(())))
    )

  // What the dispute is about. For a reconciliation, the same actions as its own page
  // (ReconciliationActions), so the mismatch can be resolved from here; doing so auto-resolves this
  // dispute, and the reload shows that.
  private def subjectCard(d: DisputeDetailPayload, onReconChanged: Observer[Unit]): HtmlElement =
    val disputeRows = Seq(
      "Account"     -> span(d.dispute.accountName),
      "Raised With" -> d.dispute.detail.map(span(_)).getOrElse(DetailList.empty)
    )
    val targetRows = (d.movement, d.reconciliation) match
      case (Some(m), _) => movementRows(m)
      case (_, Some(r)) => reconciliationRows(r)
      case _            => Seq("Target" -> span(cls := "text-md-on-surface-variant dark:text-gray-400", "Not found"))
    val actions = (d.movement, d.reconciliation) match
      case (_, Some(r)) =>
        div(
          cls := "pt-4 border-t border-md-outline dark:border-gray-700 flex flex-col gap-3",
          ReconciliationActions(r, d.reconciliationMovements, onReconChanged),
          div(Buttons.textButton("Open reconciliation →", Observer[Unit](_ => Nav.toReconciliationDetail(r.id))))
        )
      case (Some(m), _) =>
        div(Buttons.textButton("Open movement →", Observer[Unit](_ => Nav.toMovementDetail(m.id))))
      case _ => emptyNode
    div(
      cls := s"${Card.cls} p-5 min-w-0 flex flex-col gap-4",
      div(
        cls := "flex items-center justify-between gap-2",
        h3(cls := "font-medium text-md-on-surface dark:text-white", "Subject"),
        Pill(d.dispute.targetType.toString, Pill.Tone.Neutral)
      ),
      DetailList((disputeRows ++ targetRows)*),
      actions
    )

  private def movementRows(m: MovementPayload): Seq[(String, HtmlElement)] =
    Seq(
      "Reference ID"      -> DetailList.mono(m.referenceId),
      "Amount"            -> span(cls := "tabular-nums", m.amount.toString),
      "Primary Account"   -> DetailList.mono(m.inAccountId),
      "Secondary Account" -> DetailList.mono(m.outAccountId)
    )

  private def reconciliationRows(r: ReconciliationPayload): Seq[(String, HtmlElement)] =
    Seq(
      "Description"   -> (if r.description.isEmpty then DetailList.empty else span(r.description)),
      "Status"        -> ReconStatus.pill(r),
      "Balance"       -> span(cls := "tabular-nums", r.balance.toString),
      "Applied Rules" -> ReconciliationDetailPage.appliedRulesView(r.appliedRules),
      "Created At"    -> span(ViewHelper.formatDateTime(r.createdAt)),
      "Updated At"    -> span(ViewHelper.formatDateTime(r.updatedAt))
    )

  private def commentEntry(c: DisputeCommentPayload): HtmlElement =
    div(
      cls := "border-l-2 border-md-outline pl-3",
      c.newStatus.map(s => Pill(s"→ ${s.toString}", statusTone(s))).getOrElse(div()),
      c.newAssignedToName
        .map(name => Pill(s"→ Assigned to $name", Pill.Tone.Accent))
        .getOrElse(div()),
      c.body.map(b => div(cls := "text-sm mt-1 text-md-on-surface dark:text-gray-200", b)).getOrElse(div()),
      c.relatedDisputeId
        .map(id =>
          Buttons.textButton(
            s"Open dispute ${ReconciliationDetailPage.shortId(id)} →",
            Observer[Unit](_ => Nav.toExceptionDetail(id))
          )
        )
        .getOrElse(emptyNode),
      div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-1", ViewHelper.formatDateTime(c.createdAt))
    )

  private def commentForm(
    d:               DisputeDetailPayload,
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
      // For a category the system resolves itself (automaticResolution set), the backend rejects
      // any manual move into or out of Resolved: while open, Resolved is left out of the options;
      // once resolved, there's no status to change at all, so the dropdown is replaced by a note
      // on what to do instead. Comments and reassignment still work either way.
      (d.dispute.automaticResolution, d.dispute.status) match
        case (Some(_), DisputeStatusPayload.Resolved) =>
          p(
            cls := s"mb-5 text-sm ${Form.mutedTextCls}",
            "Resolved automatically, so it can't be reopened. " + d.dispute.insteadOfReopening.getOrElse("")
          )
        case (auto, _) =>
          div(
            Form.selectField(
              "newStatus",
              "Change status",
              statusOptions(d),
              "",
              v =>
                statusVar.set(v)
                validationError.set(None)
            ),
            auto
              .map(how => p(cls := s"-mt-3 mb-5 text-xs ${Form.mutedTextCls}", s"This can't be resolved by hand. $how"))
              .getOrElse(emptyNode)
          )
      ,
      // Only offered when the dispute will be open after this entry: it's open now and isn't being
      // resolved, or it's resolved and this entry reopens it. A resolved dispute can't be assigned.
      child <-- statusVar.signal.map { chosen =>
        val statusAfter = DisputeStatusPayload.values.find(_.toString == chosen).getOrElse(d.dispute.status)
        if statusAfter == DisputeStatusPayload.Resolved then
          assigneeVar.set("")
          emptyNode
        else
          Form.selectField(
            "newAssignee",
            "Assign to",
            assigneeOptions,
            "",
            v =>
              assigneeVar.set(v)
              validationError.set(None)
          )
      },
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
