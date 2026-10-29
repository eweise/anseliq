package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{AccountPayload, ReconciliationPayload}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.ViewHelper
import anseliq.view.components.{Card, Form, ReconStatus, Table}

/**
 * The ledger page's Reconciliations tab: the one list of a ledger's reconciliations, replacing the
 * old account-scoped and spec-scoped list pages. Status chips (with counts) filter it; each row
 * links to the reconciliation's own page, which is where every action on it lives — the list itself
 * carries no forms. The chosen status comes from and goes to the page's ?status=, owned by
 * LedgerPage, so a link like the Overview's "Mismatched" tile can open it pre-filtered.
 */
object LedgerReconciliationsTab:

  private val rowLimit = 200

  def apply(reconAccounts: Seq[AccountPayload], status: Signal[String], onStatus: String => Unit): HtmlElement =
    if reconAccounts.isEmpty then
      div(
        cls := s"${Card.cls} p-6 text-sm ${Form.mutedTextCls}",
        "This ledger has no recon account yet. Add one in Setup, then its reconciliations appear here."
      )
    else
      val accountVar = Var(reconAccounts.head.id.getOrElse(""))
      val counts     = Var(Map.empty[String, Int])
      val rows       = Var(Option.empty[Seq[ReconciliationPayload]])

      div(
        cls := "flex flex-col gap-4",
        accountVar.signal
          .flatMapSwitch(id =>
            ApiClient.get[Map[String, Int]](
              HttpPath.url(s"${HttpPath.account}/$id/reconciliation-counts"),
              SessionStore.token
            )
          )
          .apiBind(counts.set),
        accountVar.signal
          .combineWith(status)
          .flatMapSwitch { (id, st) =>
            val statusParam = if st.isEmpty then "" else s"&status=$st"
            ApiClient.get[Seq[ReconciliationPayload]](
              HttpPath.url(s"${HttpPath.account}/$id/reconciliations?limit=$rowLimit$statusParam"),
              SessionStore.token
            )
          }
          .apiBind(rs => rows.set(Some(rs))),
        if reconAccounts.size > 1 then
          div(
            cls := "max-w-xs",
            Form.selectField(
              "reconAccount",
              "Recon account",
              reconAccounts.map(a => a.id.getOrElse("") -> a.name),
              accountVar.now(),
              accountVar.set
            )
          )
        else emptyNode,
        div(
          cls := "flex flex-wrap gap-2",
          children <-- status.combineWith(counts.signal).map { (current, c) =>
            (ReconStatus.filters :+ ("" -> "All")).map { (value, label) =>
              val count = if value.isEmpty then c.values.sum else c.getOrElse(value, 0)
              chip(label, count, selected = value == current, onSelect = () => onStatus(value))
            }
          }
        ),
        child <-- rows.signal.combineWith(status).map {
          case (None, _) => div(cls := s"text-sm ${Form.mutedTextCls}", "Loading…")
          case (Some(rs), st) if rs.isEmpty =>
            val label = ReconStatus.filters.find(_._1 == st).map(_._2.toLowerCase + " ").getOrElse("")
            div(cls := s"${Card.cls} p-6 text-sm ${Form.mutedTextCls}", s"No ${label}reconciliations.")
          case (Some(rs), _) =>
            div(
              Table.create(
                Seq("Reconciliation", "Status", "Matched on", "Balance", "Created"),
                rs.map(r =>
                  Seq(
                    a(
                      href := s"/components/reconciliations/${r.id}",
                      cls  := "text-md-primary hover:underline cursor-pointer",
                      onClick --> { e =>
                        e.preventDefault()
                        Nav.toReconciliationDetail(r.id)
                      },
                      span(cls := "font-mono", ReconciliationDetailPage.shortId(r.id)),
                      if r.description.trim.nonEmpty then
                        span(cls := "ml-2 text-md-on-surface-variant dark:text-gray-400", r.description)
                      else emptyNode
                    ),
                    ReconStatus.pill(r),
                    span(r.appliedRules.summary),
                    span(cls := "tabular-nums", money(r.balance)),
                    span(ViewHelper.formatDateTime(r.createdAt))
                  )
                ),
                numCols = Set(3)
              ),
              if rs.size >= rowLimit then
                p(cls := s"mt-2 text-xs ${Form.mutedTextCls}", s"Showing the $rowLimit most recent.")
              else emptyNode
            )
        }
      )

  private def chip(label: String, count: Int, selected: Boolean, onSelect: () => Unit): HtmlElement =
    val base = "inline-flex items-center gap-2 rounded-full border px-3 py-1 text-sm transition-colors"
    val tone =
      if selected then
        "border-md-primary bg-md-primary-container text-md-on-primary-container dark:bg-md-primary-container-dark dark:text-md-on-primary-container-dark font-medium"
      else "border-md-outline dark:border-gray-600 text-md-on-surface dark:text-gray-200 hover:border-md-primary"
    button(
      typ          := "button",
      cls          := s"$base $tone",
      aria.pressed := selected.toString,
      onClick --> (_ => onSelect()),
      span(label),
      span(cls := "tabular-nums text-xs opacity-70", count.toString)
    )

  private def money(amount: BigDecimal): String =
    if amount < 0 then f"-$$${-amount}%.2f" else f"$$${amount}%.2f"
