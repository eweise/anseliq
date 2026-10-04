package anseliq.view.components

import com.raquo.laminar.api.L.*

// Label/value pairs for detail-page cards: a two-column grid whose label column is sized to the
// longest label, so values line up instead of trailing "Label: value" text at ragged offsets.
object DetailList:

  def apply(rows: (String, Modifier[HtmlElement])*): HtmlElement =
    dl(
      cls := "grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm",
      rows.flatMap { (label, value) =>
        Seq(
          dt(cls := "text-md-on-surface-variant dark:text-gray-400", label),
          dd(cls := "min-w-0 text-md-on-surface dark:text-gray-200", value)
        )
      }
    )

  // Placeholder for an absent value, so an empty row still reads as intentionally blank.
  val empty: HtmlElement = span(cls := "text-gray-400 dark:text-gray-500", "—")

  // IDs and other machine values: monospace, and allowed to wrap anywhere rather than overflow.
  def mono(text: String): HtmlElement = span(cls := "font-mono text-xs break-all", text)
