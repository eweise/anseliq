package perfectpair.view

import perfectpair.model.Msg
import perfectpair.page.Page
import perfectpair.view.components.Icons
import tyrian.Html.*
import tyrian.{Elem, Html}

object MainContainer:

  private val theFooter =
    footer(cls := "w-full bg-purple-600 dark:bg-purple-900 border-t dark:border-gray-700 text-white py-4")(
      div(cls := "container mx-auto text-center")(
        p(cls := "text-sm")(
          "Created by Scala with Love © 2025. All rights reserved."
        ),
        br(),
        a(
          href := "https://github.com/linux-root/tyrian-flowbite.g8",
          cls  := "text-blue-400 hover:underline ml-2 flex items-center justify-center space-x-1 inline-flex"
        )(Icons.github)
      )
    )

  def apply(content: Html[Msg], isDark: Boolean, isLoggedIn: Boolean): Html[Msg] =
    div(cls := (if isDark then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"))(
      navbar(isDark, isLoggedIn),
      leftNav(),
      main(cls := "flex flex-col min-h-screen mt-20 ml-64 bg-md-surface-variant dark:bg-gray-900")(content),
      theFooter
    )

  def navbar(isDark: Boolean, isLoggedIn: Boolean): Html[Msg] =
    nav(
      cls := "bg-purple-600 dark:bg-purple-900 fixed w-full z-20 top-0 start-0 border-b border-gray-200 dark:border-gray-700"
    )(
      div(cls := "flex flex-wrap items-center justify-between p-4")(
        a(href := "/", cls := "flex items-center")(
          img(
            src   := "assets/images/perfectpair.png",
            width := "100px"
          )
        ),
        div(cls := "flex")(
          darkModeSwitchButton(isDark),
          if isLoggedIn then controlButton(Msg.Logout, Icons.logout) else div()
        )
      )
    )

  private def controlButton(clickMsg: Msg, child: Elem[Msg]) =
    val dark   = "dark:text-white dark:hover:bg-purple-700 dark:focus:ring-purple-200"
    val normal = "text-white hover:bg-purple-700 focus:ring-purple-100"
    button(
      onClick(clickMsg),
      cls := s"transition-transform duration-300 ease-in-out hover:scale-105 p-4 me-2 mb-2 rounded-full focus:outline-none $normal $dark"
    )(child)

  private def darkModeSwitchButton(isDark: Boolean) =
    val icon = if isDark then Icons.sun else Icons.moon
    controlButton(Msg.ToggleDarkMode, icon)

  private def leftNav(): Html[Msg] =
    aside(
      cls := "fixed left-0 top-20 bottom-0 w-64 z-10 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 shadow-lg overflow-y-auto"
    )(
      nav(cls := "p-4")(
        ul(cls := "space-y-2")(
          navItem("Home", Page.Home, Icons.home),
          navItem("Components", Page.Toggle, Icons.component),
          navItem("Fund Flows", Page.FundFlows, Icons.component),
          navItem("Create Fund Flow", Page.MaintainFundFlow, Icons.component),
          navItem("Accounts", Page.Accounts, Icons.component),
          navSeparator(),
          navItemExternal("Dashboard", "#", Icons.dashboard),
          navItemExternal("Profile", "#", Icons.user),
          navItemExternal("Settings", "#", Icons.settings),
          navSeparator(),
          navItemExternal("Analytics", "#", Icons.chart),
          navItemExternal("Reports", "#", Icons.document)
        )
      )
    )

  private def navItem(label: String, page: Page, icon: Elem[Msg]): Html[Msg] =
    li()(
      a(
        href := page.path,
        onClick(Msg.NavigateTo(page)),
        cls := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200"
      )(
        span(cls := "w-5 h-5")(icon),
        span()(text(label))
      )
    )

  private def navItemExternal(label: String, linkHref: String, icon: Elem[Msg]): Html[Msg] =
    li()(
      a(
        href := linkHref,
        cls  := "flex items-center space-x-3 px-4 py-3 rounded-lg text-gray-700 dark:text-gray-200 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors duration-200"
      )(
        span(cls := "w-5 h-5")(icon),
        span()(text(label))
      )
    )

  private def navSeparator(): Html[Msg] =
    li(cls := "py-2")(
      hr(cls := "border-gray-300 dark:border-gray-600")
    )
