package onparity.view.components

import com.raquo.laminar.api.L.*

object Modal:

  // Fixed full-viewport backdrop + centered card, so the page underneath (e.g. the fund flow
  // diagram) stays mounted and visible instead of being navigated away from. Clicking the backdrop
  // itself (not the card) closes it — checking target == currentTarget rather than stopping
  // propagation on the card, since a click that starts and ends inside the card never has the
  // backdrop div as its target in the first place.
  def apply(onClose: () => Unit, content: HtmlElement): HtmlElement =
    div(
      cls := "fixed inset-0 z-50 flex items-start justify-center pt-20 px-4 overflow-y-auto bg-black/40",
      onClick --> { e => if e.target == e.currentTarget then onClose() },
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        content
      )
    )
