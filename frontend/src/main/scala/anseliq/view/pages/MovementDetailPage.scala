package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  ExceptionCategoryPayload,
  ExceptionPayload,
  ExceptionTargetPayload,
  MovementDetailPayload,
  RaiseExceptionRequest
}
import anseliq.common.model.model.Money
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{Buttons, Card, DetailList, Form, MovementLabels, PageHeader, Pill, ReconStatus}

object MovementDetailPage:

  case class State(detail: Option[MovementDetailPayload] = None)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[MovementDetailPayload](s"${HttpPath.movement}/$id", SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          div(
            cls := "relative flex flex-col gap-6",
            PageHeader.ledgerBreadcrumb(
              d.ledger,
              d.ledger.map(_ => "Movements" -> "Movements"),
              ReconciliationDetailPage.shortId(d.id) -> None
            ),
            div(
              cls := "flex flex-wrap items-center gap-3",
              h2(
                cls := "text-2xl font-medium text-gray-900 dark:text-white",
                "Movement ",
                span(cls := "font-mono", ReconciliationDetailPage.shortId(d.id)),
                span(cls := "ml-3 tabular-nums", d.amount.toString)
              ),
              d.movementType
                .map(_ => Pill(MovementLabels.typeLabel(d.movementType, d.adjustmentType), Pill.Tone.Neutral))
                .getOrElse(emptyNode)
            ),
            raiseException(d.id, Observer[Unit](_ => loadBus.emit(()))),
            reconciliationSection(d),
            if d.openExceptions.isEmpty then emptyNode else exceptionsSection(d.openExceptions),
            movementSection(d),
            propertiesSection("Properties", d.properties),
            propertiesSection("Metadata", d.metaData)
          )
      }
    )

  // Where the movement went: the reconciliation it's in (linked, with its status), or a note that
  // it's still waiting to be matched.
  private def reconciliationSection(d: MovementDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-wrap items-center gap-3 text-sm",
      h3(cls := "font-medium text-md-on-surface dark:text-white mr-2", "Reconciliation"),
      d.reconciliation match
        case Some(r) =>
          Seq(
            span(cls := "text-md-on-surface dark:text-gray-200", "Reconciled in"),
            a(
              href := s"/components/reconciliations/${r.id}",
              cls  := "font-mono text-md-primary hover:underline cursor-pointer",
              onClick --> { e =>
                e.preventDefault()
                Nav.toReconciliationDetail(r.id)
              },
              ReconciliationDetailPage.shortId(r.id)
            ),
            ReconStatus.pill(r)
          )
        case None =>
          Seq(
            span(
              cls := Form.mutedTextCls,
              "Not reconciled yet. It's matched on the next reconciliation run, or by hand with Manual match on the ledger page."
            )
          )
    )

  private def exceptionsSection(exceptions: Seq[ExceptionPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-3",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Open exceptions"),
      exceptions.map { exception =>
        div(
          cls := "flex flex-wrap items-center gap-3 text-sm",
          span(cls := "font-medium text-md-on-surface dark:text-white", exception.category.toString),
          Pill(exception.status.toString, ExceptionDetailPage.statusTone(exception.status)),
          span(cls := Form.mutedTextCls, exception.assignedToName.fold("Unassigned")(n => s"Assigned to $n")),
          Buttons.textButton("Open thread", Observer[Unit](_ => Nav.toExceptionDetail(exception.id)))
        )
      }
    )

  // The categories a person can raise against a single movement (see ExceptionCategory.validTargets
  // on the backend); the reconciliation- and file-level ones are raised by the system.
  private val movementCategories = Seq(
    ExceptionCategoryPayload.MissingCounterpart -> "Missing counterpart",
    ExceptionCategoryPayload.Duplicate          -> "Duplicate",
    ExceptionCategoryPayload.TimingDifference   -> "Timing difference",
    ExceptionCategoryPayload.DataQuality        -> "Data quality"
  )

  private def raiseException(movementId: String, onRaised: Observer[Unit]): HtmlElement =
    val open     = Var(false)
    val category = Var(movementCategories.head._1)
    val detail   = Var("")
    val raiseBus = EventBus[Unit]()
    div(
      cls := "flex flex-col gap-3",
      raiseBus.events.flatMapSwitch { _ =>
        ApiClient.post[ExceptionPayload, RaiseExceptionRequest](
          HttpPath.exception,
          RaiseExceptionRequest(
            referenceId = movementId,
            targetType  = ExceptionTargetPayload.Movement,
            category    = category.now(),
            detail      = Option(detail.now().trim).filter(_.nonEmpty)
          ),
          SessionStore.token
        )
      }.apiBind { _ =>
        open.set(false)
        onRaised.onNext(())
      },
      child <-- open.signal.map {
        case false if !SessionStore.canEdit => emptyNode
        case false =>
          div(Buttons.textButton("Raise exception", Observer[Unit](_ => open.set(true))))
        case true =>
          div(
            cls := "flex flex-col gap-3 p-4 rounded-lg border border-md-outline dark:border-gray-700 bg-md-surface-variant dark:bg-md-surface-alt-dark max-w-xl",
            div(cls := "font-medium text-md-on-surface dark:text-white", "Raise an exception on this movement"),
            Form.selectField(
              "exceptionCategory",
              "Category",
              movementCategories.map((c, label) => c.toString -> label),
              category.now().toString,
              v => category.set(ExceptionCategoryPayload.valueOf(v))
            ),
            Form.field("exceptionDetail", "Detail (optional)", "", detail.set),
            div(
              cls := "flex gap-2",
              Buttons.linkButton("Raise", Observer[Unit](_ => raiseBus.emit(()))),
              Buttons.textButton("Cancel", Observer[Unit](_ => open.set(false)))
            )
          )
      }
    )

  private def movementSection(d: MovementDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Movement"),
      DetailList(
        "ID"                -> DetailList.mono(d.id),
        "Reference ID"      -> DetailList.mono(d.referenceId),
        "Type"              -> span(MovementLabels.typeLabel(d.movementType, d.adjustmentType)),
        "Adjustment Note"   -> d.adjustmentNote.map(span(_)).getOrElse(DetailList.empty),
        "Amount"            -> span(cls := "tabular-nums", d.amount.toString),
        "Movement Spec"     -> span(d.movementSpecName),
        "Primary Account"   -> accountView(d.inAccountName, d.inAccountBalance),
        "Secondary Account" -> accountView(d.outAccountName, d.outAccountBalance)
      )
    )

  // Account name, with its current balance as secondary detail rather than a parenthetical.
  private def accountView(name: String, balance: Money): HtmlElement =
    span(
      name,
      span(
        cls := "ml-2 text-xs text-md-on-surface-variant dark:text-gray-400 tabular-nums",
        s"Balance ${balance}"
      )
    )

  private def propertiesSection(title: String, props: Map[String, String]): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", title),
      if props.isEmpty then div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "None")
      else
        DetailList(
          props.toSeq
            .sortBy(_._1)
            .map((k, v) => k -> (if v.isEmpty then DetailList.empty else span(cls := "break-all", v)))*
        )
    )
