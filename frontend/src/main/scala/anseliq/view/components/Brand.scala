package anseliq.view.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s

// The AnselIQ logo, shared by the sidebar and the login page so they can't drift apart. Same mark as
// the marketing site (website/): an aperture, a graphite tile with a silver ring and a "safelight"
// amber center, beside the "AnselIQ" wordmark.
object Brand:

  def mark(sizePx: Int = 28): SvgElement =
    s.svg(
      s.width     := sizePx.toString,
      s.height    := sizePx.toString,
      s.viewBox   := "0 0 32 32",
      aria.hidden := true,
      s.rect(s.width := "32", s.height := "32", s.rx := "8", s.fill   := "#212830"),
      s.circle(s.cx  := "16", s.cy     := "16", s.r  := "9", s.fill   := "none", s.stroke := "#9aa4ae", s.strokeWidth := "2"),
      s.circle(s.cx  := "16", s.cy     := "16", s.r  := "3.5", s.fill := "#e8992f")
    )

  // "Ansel" in the surrounding text color, "IQ" in amber (a deeper shade on light backgrounds, where
  // the bright amber wouldn't have enough contrast).
  def wordmark(textCls: String = "text-xl"): HtmlElement =
    span(
      cls := s"font-semibold tracking-tight text-gray-900 dark:text-white $textCls",
      "Ansel",
      span(cls := "text-[#a3600f] dark:text-[#e8992f]", "IQ")
    )

  def logo(markPx: Int = 28, textCls: String = "text-xl"): HtmlElement =
    div(cls := "flex items-center gap-2.5", mark(markPx), wordmark(textCls))
