package onparity.view.layout

import com.raquo.laminar.api.L.*
import onparity.routing.Nav
import onparity.state.SessionStore
import onparity.view.components.Icons

object LeftNav:

  def apply(): HtmlElement =
    // A snapshot at mount time, not a reactive binding: role doesn't change mid-session without a
    // fresh login (which re-mounts the whole app), so re-deriving this on every SessionStore
    // change would be work with no observable effect.
    val isAdmin = SessionStore.currentUserNow.exists(_.role == "Admin")

    asideTag(
      cls := "fixed left-0 top-0 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto",
      navTag(
        cls := "p-4",
        div(
          cls := "px-4 pb-4 mb-2 border-b border-gray-200 dark:border-gray-700",
          div(cls := "text-xl font-semibold text-gray-900 dark:text-white leading-tight", "Stinson Beach"),
          div(cls := "text-xs font-medium uppercase tracking-wide text-gray-400 dark:text-gray-500", "Recon Platform")
        ),
        ul(
          (cls := "space-y-1") +: groups(isAdmin).flatMap(navGroupItems)*
        ),
        div(
          cls := "mt-4 pt-4 border-t border-gray-300 dark:border-gray-600",
          button(
            cls := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors duration-200 w-full text-left",
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

  private def groups(isAdmin: Boolean): Seq[NavGroup] = Seq(
    NavGroup(
      "Overview",
      Seq(
        NavItem("Dashboard", "/components/dashboard", Icons.home)
      )
    ),
    NavGroup(
      "Operations",
      Seq(
        NavItem("Ledgers", "/components/fund-flows", Icons.document),
        NavItem("Exceptions & Disputes", "/components/exceptions", Icons.document),
        NavItem("File Inventory", "/components/file-inventory", Icons.document),
        NavItem("Jobs", "/components/jobs", Icons.chart),
        NavItem("Events", "/components/events", Icons.document)
      )
    ),
    NavGroup(
      "Configure",
      Seq(
        NavItem("File Feeds", "/components/file-feeds", Icons.settings),
        NavItem("Recon Rules", "/components/coming-soon/recon-rules", Icons.settings)
      )
    ),
    NavGroup(
      "Insights",
      Seq(
        NavItem("Reports & Analytics", "/components/reports", Icons.chart)
      )
    ),
    NavGroup(
      "Admin",
      (if isAdmin then
         Seq(
           NavItem("Users & Roles", "/components/users", Icons.user),
           NavItem("Fix Bad File Ingest", "/components/fix-bad-file", Icons.document)
         )
       else Seq.empty)
        :+ NavItem("Settings", "/components/coming-soon/settings", Icons.settings)
    )
  )

  private def navGroupItems(g: NavGroup): Seq[HtmlElement] =
    li(
      cls := "px-4 pt-4 pb-1 text-xs font-semibold uppercase tracking-wide text-gray-400 dark:text-gray-500 first:pt-0",
      g.label
    ) +: g.items.map(navItem)

  private def navItem(item: NavItem): HtmlElement =
    li(
      a(
        href := item.href,
        cls  := "flex items-center space-x-3 px-4 py-2.5 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors duration-200",
        onClick --> { e =>
          e.preventDefault()
          Nav.push(item.href)
        },
        span(cls := "w-5 h-5", item.icon),
        span(item.label)
      )
    )

  private case class NavItem(label: String, href: String, icon: HtmlElement)

  private case class NavGroup(label: String, items: Seq[NavItem])
