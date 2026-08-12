package perfectpair.view.layout

import com.raquo.laminar.api.L.*
import perfectpair.state.AppState
import perfectpair.view.components.Icons

object MainLayout:

  def apply(content: HtmlElement): HtmlElement =
    div(
      cls <-- AppState.darkMode.signal.map(d =>
        if d then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"
      ),
      LeftNav(),
      topBar(),
      mainTag(cls := "flex flex-col min-h-screen ml-64 bg-md-surface-variant dark:bg-gray-900", content)
    )

  private def topBar(): HtmlElement =
    div(
      cls := "fixed top-0 left-64 right-0 z-10 flex items-center justify-between px-6 py-3 bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700",
      child <-- AppState.globalError.signal.map {
        case None      => div()
        case Some(msg) => errorMessage(msg)
      },
      button(
        cls := "flex items-center justify-center w-9 h-9 rounded-full text-gray-600 dark:text-gray-300 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors",
        typ := "button",
        onClick --> (_ => AppState.darkMode.update(!_)),
        child <-- AppState.darkMode.signal.map(d => if d then Icons.sun else Icons.moon)
      )
    )

  private def errorMessage(message: String): HtmlElement =
    div(
      cls := "flex items-center gap-2 px-3 py-1 rounded bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-100 text-sm",
      span(cls := "font-medium", "Error:"),
      span(message),
      button(
        cls := "ml-2 text-red-600 dark:text-red-200 hover:text-red-900 dark:hover:text-red-50 font-bold text-lg leading-none",
        onClick --> (_ => AppState.globalError.set(None)),
        "×"
      )
    )
