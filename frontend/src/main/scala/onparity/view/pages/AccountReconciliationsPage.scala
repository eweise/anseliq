package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.ReconciliationPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{PageHeader, Table}

object AccountReconciliationsPage:

  case class State(recons: Seq[ReconciliationPayload] = Seq.empty)

  def apply(accountId: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/reconciliations"),
        SessionStore.token
      )

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(rs => state.update(_.copy(recons = rs))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.back())),
        PageHeader.title("Recent Reconciliations")
      ),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Balance", "Adjustment Reason", "Created At", "Updated At"),
            s.recons.map(r =>
              Seq(
                a(
                  href := s"/components/reconciliations/${r.id}",
                  cls  := "text-md-primary hover:underline cursor-pointer",
                  onClick --> { e =>
                    e.preventDefault()
                    Nav.toReconciliationDetail(r.id)
                  },
                  // description can be blank on real data — fall back to something visibly clickable
                  // rather than an empty, invisible link.
                  if r.description.trim.nonEmpty then r.description else "View"
                ),
                ReconciliationsPage.statusPill(r),
                span(r.appliedRules),
                span(r.balance.toString),
                span(r.adjustmentReason.getOrElse("")),
                span(ViewHelper.formatDateTime(r.createdAt)),
                span(ViewHelper.formatDateTime(r.updatedAt))
              )
            ),
            numCols = Set(3)
          )
        }
      )
    )
