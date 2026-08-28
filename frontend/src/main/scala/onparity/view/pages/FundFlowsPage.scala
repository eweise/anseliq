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
          Table.create(
            Seq("Name", "Created At", "Updated At", "", ""),
            ffs.sortBy(_.name).map { ff =>
              Seq(
                a(
                  href := "/components/fund-flow",
                  cls  := "block px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-purple-100 dark:hover:bg-purple-900 rounded-lg transition-colors duration-200",
                  onClick --> { e =>
                    e.preventDefault()
                    Nav.toFundFlow(ff)
                  },
                  ff.name
                ),
                span(ff.createdAt.map(ViewHelper.formatDateTime).getOrElse("")),
                span(ff.updatedAt.map(ViewHelper.formatDateTime).getOrElse(""))
              )
            }
          )
        }
      )
    )
