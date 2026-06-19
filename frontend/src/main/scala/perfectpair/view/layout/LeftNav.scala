package perfectpair.view.layout

import com.raquo.laminar.api.L.*
import perfectpair.common.model.FundFlowPayload
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.Icons

object LeftNav:

  def apply(): HtmlElement =
    asideTag(
      cls := "fixed left-0 top-0 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto",
      navTag(
        cls := "p-4",
        ul(
          cls := "space-y-2",
          navItem("Home", "/", Icons.home),
          fundFlowNavSection(),
          navItem("Create Fund Flow", "/components/maintain-fund-flow", Icons.component),
          navItem("Jobs", "/components/jobs", Icons.document),
          navItem("Events", "/components/events", Icons.document),
          navSeparator(),
          navItem("Dashboard", "#", Icons.dashboard),
          navItem("Profile", "#", Icons.user),
          navItem("Settings", "#", Icons.settings),
          navSeparator(),
          navItem("Analytics", "#", Icons.chart),
          navItem("Reports", "#", Icons.document),
          navSeparator(),
          li(
            button(
              cls := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 hover:bg-purple-100 transition-colors duration-200 w-full text-left",
              onClick --> (_ =>
                SessionStore.clear()
                Nav.push("/login")
              ),
              span(cls := "w-5 h-5", Icons.logout),
              span("Logout")
            )
          )
        )
      )
    )

  private def navItem(lbl: String, itemHref: String, icon: HtmlElement): HtmlElement =
    li(
      a(
        href := itemHref,
        cls  := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200",
        span(cls := "w-5 h-5", icon),
        span(lbl)
      )
    )

  private def navSeparator(): HtmlElement =
    li(cls := "py-2", hr(cls := "border-gray-300 dark:border-gray-600"))

  private def fundFlowNavSection(): HtmlElement =
    li(
      div(
        cls := "flex items-center justify-between rounded-lg text-gray-700 dark:text-gray-200",
        a(
          href := "/components/fund-flows",
          cls  := "flex items-center space-x-3 flex-1 px-4 py-3 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200 rounded-lg",
          span(cls := "w-5 h-5", Icons.component),
          span("Fund Flows")
        ),
        button(
          cls := "px-3 py-3 rounded hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200 text-gray-500",
          onClick --> (_ => AppState.fundFlowNavOpen.update(!_)),
          child <-- AppState.fundFlowNavOpen.signal.map(open => if open then "▼" else "▶")
        )
      ),
      child <-- AppState.fundFlowNavOpen.signal.map { open =>
        if !open then ul()
        else
          ul(
            cls := "ml-6 mt-1 space-y-1",
            children <-- AppState.fundFlows.signal.map(
              _.map(ff =>
                li(
                  a(
                    href := "/components/fund-flow",
                    cls  := "block px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-purple-100 dark:hover:bg-purple-900 rounded-lg transition-colors duration-200",
                    onClick --> { e =>
                      e.preventDefault()
                      Nav.toFundFlow(ff)
                    },
                    ff.name
                  )
                )
              )
            )
          )
      }
    )
