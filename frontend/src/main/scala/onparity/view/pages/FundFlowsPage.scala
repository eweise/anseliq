package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.FundFlowPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, PageHeader, Table}
import upickle.default.*

object FundFlowsPage:

  def apply(): HtmlElement =
    val fundFlows = Var(Seq.empty[FundFlowPayload])

    PageHeader.page(
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.url(HttpPath.fundFlows), SessionStore.token).apiBind { ffs =>
        fundFlows.set(ffs)
        AppState.fundFlows.set(ffs)
      },
      PageHeader.titleRow(
        cls := "flex justify-between items-center",
        PageHeader.title("Ledgers"),
        Buttons.linkButton(
          "Create",
          Observer[Unit](_ =>
            AppState.activeFundFlow.set(None)
            Nav.toMaintainFundFlow()
          )
        )
      ),
      div(
        cls := "w-full px-8",
        child <-- fundFlows.signal.map { ffs =>
          // Every cell (not just Name) carries its own click-to-navigate handler, so the whole row
          // is clickable — Table is a shared component with no row-level click plumbing, and adding
          // it there would need to survive this page's own sort-by-column reordering, whereas
          // keeping the handler bundled into each cell's own HtmlElement means it just travels along
          // with that cell when Table re-sorts the display order. The hover *highlight* is still
          // driven by Table itself (hoverableRows), so the whole row lights up as one bar rather
          // than three separately-hovering cells.
          def cell(content: String, ff: FundFlowPayload): HtmlElement =
            a(
              href := "/components/fund-flow",
              cls  := "block px-4 py-2 text-sm text-gray-600 dark:text-gray-300",
              onClick --> { e =>
                e.preventDefault()
                Nav.toFundFlow(ff)
              },
              content
            )

          Table.create(
            Seq("Name", "Created At", "Updated At", "", ""),
            ffs.sortBy(_.name).map { ff =>
              Seq(
                cell(ff.name, ff),
                cell(ff.createdAt.map(ViewHelper.formatDateTime).getOrElse(""), ff),
                cell(ff.updatedAt.map(ViewHelper.formatDateTime).getOrElse(""), ff)
              )
            },
            hoverableRows = true
          )
        }
      )
    )
