package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.ReconciliationDetailPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Card, PageHeader, Table}

object ReconciliationDetailPage:

  case class State(detail: Option[ReconciliationDetailPayload] = None)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[ReconciliationDetailPayload](HttpPath.url(s"${HttpPath.reconciliation}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.backLink(Observer[Unit](_ => Nav.back())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          div(
            PageHeader.title(s"Reconciliation — ${d.reconciliation.description}"),
            reconciliationSection(d),
            reconSpecSection(d),
            movementsSection(d)
          )
      }
    )

  private def reconciliationSection(d: ReconciliationDetailPayload): HtmlElement =
    val r = d.reconciliation
    div(
      cls := s"${Card.cls} p-4 mb-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Reconciliation"),
      div(
        cls := "text-sm space-y-1 text-md-on-surface dark:text-gray-200",
        div(s"ID: ${r.id}"),
        div(s"Description: ${r.description}"),
        div(s"Applied Rules: ${r.appliedRules}"),
        div(cls := "flex items-center gap-2", span("Status:"), ReconciliationsPage.statusPill(r)),
        div(s"Balance: ${r.balance}"),
        div(s"Adjustment Reason: ${r.adjustmentReason.getOrElse("")}"),
        div(s"Created At: ${ViewHelper.formatDateTime(r.createdAt)}"),
        div(s"Updated At: ${ViewHelper.formatDateTime(r.updatedAt)}")
      )
    )

  private def reconSpecSection(d: ReconciliationDetailPayload): HtmlElement =
    val s = d.spec
    div(
      cls := s"${Card.cls} p-4 mb-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Reconciliation Spec"),
      div(
        cls := "text-sm space-y-1 text-md-on-surface dark:text-gray-200",
        div(s"ID: ${s.id}"),
        div(s"Account: ${s.accountName}"),
        div(s"Aging Limit: ${s.agingLimit}"),
        div(s"Relationship: ${s.relationship}"),
        div(cls := "break-all", s"Rule JSON: ${s.ruleJson}")
      )
    )

  private def movementsSection(d: ReconciliationDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Related Movements"),
      Table.create(
        Seq("ID", "Amount"),
        d.movements.map(m =>
          Seq(
            a(
              href := s"/components/movements/${m.id}",
              cls  := "text-md-on-surface dark:text-gray-200 hover:underline cursor-pointer",
              onClick --> { e =>
                e.preventDefault()
                Nav.toMovementDetail(m.id)
              },
              m.id
            ),
            span(m.amount.toString)
          )
        )
      )
    )
