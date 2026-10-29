package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.FundFlowPayload
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{Buttons, PageHeader, Table}
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
        Option.when(SessionStore.canEdit)(
          Buttons.linkButton(
            "Create",
            Observer[Unit](_ =>
              AppState.activeFundFlow.set(None)
              Nav.toMaintainFundFlow()
            )
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
