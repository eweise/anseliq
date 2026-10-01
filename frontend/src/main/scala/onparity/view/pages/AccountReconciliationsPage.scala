package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.ReconciliationPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import frontroute.BrowserNavigation
import onparity.view.components.{Form, PageHeader, Table}
import org.scalajs.dom

object AccountReconciliationsPage:

  case class State(recons: Seq[ReconciliationPayload] = Seq.empty)

  // Values are ReconciliationStatus names, as the backend's ?status= filter expects; "" = all.
  private val statusOptions = Seq(
    ""           -> "All",
    "InProgress" -> "In Progress",
    "Mismatched" -> "Mismatched",
    "Final"      -> "Final",
    "Voided"     -> "Voided",
    "Reversed"   -> "Reversed"
  )

  // The filter lives in ?status= so a link (e.g. the fund flow Overview's "In progress" tile) can
  // open the page pre-filtered, and a reload keeps it.
  private def statusFromUrl(): String =
    Option(new dom.URLSearchParams(dom.window.location.search).get("status"))
      .filter(s => statusOptions.exists(_._1 == s))
      .getOrElse("")

  def apply(accountId: String): HtmlElement =
    val state   = Var(State())
    val status  = Var(statusFromUrl())
    val loadBus = EventBus[Unit]()

    def load() =
      val query = Option(status.now()).filter(_.nonEmpty).fold("")(s => s"?status=$s")
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/reconciliations$query"),
        SessionStore.token
      )

    // replaceState rather than push: changing the filter shouldn't add a history entry, so back
    // still returns to wherever the page was opened from. popStateEvent = false since nothing here
    // needs to react to its own URL update.
    def selectStatus(s: String): Unit =
      status.set(s)
      BrowserNavigation.replaceState(
        url = Nav.accountReconciliationsUrl(accountId, Option(s).filter(_.nonEmpty)),
        popStateEvent = false
      )
      loadBus.emit(())

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(rs => state.update(_.copy(recons = rs))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.back())),
        PageHeader.title("Recent Reconciliations")
      ),
      div(
        cls := "w-full px-8",
        div(
          cls := "max-w-xs",
          Form.selectField("reconStatus", "Status", statusOptions, status.now(), selectStatus)
        ),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Balance", "Adjustment Reason", "Created At", "Updated At"),
            s.recons.map(r =>
              Seq(
                a(
                  href := s"/components/reconciliations/${r.id}",
                  cls  := "text-md-on-surface dark:text-gray-200 hover:underline cursor-pointer",
                  onClick --> { e =>
                    e.preventDefault()
                    Nav.toReconciliationDetail(r.id)
                  },
                  // description can be blank on real data — fall back to something visibly clickable
                  // rather than an empty, invisible link.
                  if r.description.trim.nonEmpty then r.description else "View"
                ),
                ReconciliationsPage.statusPill(r),
                span(r.appliedRules.summary),
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
