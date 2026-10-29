package anseliq.view.layout

import com.raquo.laminar.api.L.*
import anseliq.routing.Nav
import anseliq.state.SessionStore
import anseliq.view.components.{Brand, Icons}

object LeftNav:

  def apply(): HtmlElement =
    // MainLayout/LeftNav is mounted exactly once at app boot (WebApp.scala), before any login has
    // necessarily happened yet — a fresh login is a client-side Nav.push, not a page reload, so a
    // one-time SessionStore.currentUserNow snapshot here would freeze isAdmin at whatever it was
    // (usually false) for the rest of the session. Bound reactively instead, .distinct so logging
    // out and back in as the same role doesn't needlessly rebuild the nav.
    val isAdmin = SessionStore.currentUser.signal.map(_.exists(_.role == "Admin")).distinct

    asideTag(
      cls := "fixed left-0 top-0 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto",
      navTag(
        cls := "p-4",
        div(
          cls := "px-4 pb-4 mb-2 border-b border-gray-200 dark:border-gray-700",
          Brand.logo(),
          div(
            cls := "mt-1 text-xs font-medium uppercase tracking-wide text-gray-400 dark:text-gray-500",
            "Reconciliation"
          )
        ),
        ul(
          cls := "space-y-1",
          children <-- isAdmin.map(groups(_).flatMap(navGroupItems))
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

  // Grouped by what people come here to do: work the queue, look at a ledger, check on the data
  // flowing in, or read reports. Fix Bad File lives on File Inventory rows now (it acts on one
  // file), and the "coming soon" placeholders are gone until those pages exist.
  private def groups(isAdmin: Boolean): Seq[NavGroup] = Seq(
    NavGroup(
      "Work",
      Seq(
        NavItem("Dashboard", "/components/dashboard", Icons.home),
        NavItem("Exceptions", "/components/exceptions", Icons.document)
      )
    ),
    NavGroup(
      "Ledgers",
      Seq(NavItem("Ledgers", "/components/ledgers", Icons.document))
    ),
    NavGroup(
      "Data",
      Seq(
        NavItem("File Feeds", "/components/file-feeds", Icons.settings),
        NavItem("File Inventory", "/components/file-inventory", Icons.document),
        NavItem("Jobs", "/components/jobs", Icons.chart),
        NavItem("Events", "/components/events", Icons.document)
      )
    ),
    NavGroup(
      "Insights",
      Seq(NavItem("Reports", "/components/reports", Icons.chart))
    )
  ) ++ (if isAdmin then Seq(NavGroup("Admin", Seq(NavItem("Users", "/components/users", Icons.user)))) else Seq.empty)

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
