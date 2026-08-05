package perfectpair.view.layout

import com.raquo.laminar.api.L.*
import perfectpair.routing.Nav
import perfectpair.state.SessionStore
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
//          fundFlowNavSection(),
          navItem("Ledgers", "/components/fund-flows", Icons.document),
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
