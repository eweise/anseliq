package perfectpair.view.layout

import com.raquo.laminar.api.L.*
import perfectpair.state.AppState

object MainLayout:

  def apply(content: HtmlElement): HtmlElement =
    div(
      cls <-- AppState.darkMode.signal.map(d =>
        if d then "dark bg-gray-900" else "not-dark-mode bg-md-surface-variant"
      ),
      LeftNav(),
      child <-- AppState.globalError.signal.map {
        case None      => div()
        case Some(msg) => errorBanner(msg)
      },
      mainTag(cls := "flex flex-col min-h-screen ml-64 bg-md-surface-variant dark:bg-gray-900", content)
    )

  private def errorBanner(message: String): HtmlElement =
    div(cls := "fixed top-0 left-64 right-0 z-10 flex items-center justify-between px-6 py-3 bg-red-100 border-b border-red-300 text-red-800",
      div(cls := "flex items-center gap-2",
        span(cls := "font-medium", "Error:"),
        span(message)
      ),
      button(
        cls := "ml-4 text-red-600 hover:text-red-900 font-bold text-lg leading-none",
        onClick --> (_ => AppState.globalError.set(None)),
        "×"
      )
    )
