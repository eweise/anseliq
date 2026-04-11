package perfectpair.view.components

import tyrian.{Elem, Html}
import tyrian.Html.*
object Table:
  def create[M](
    headers: Seq[String],
    rows: Seq[Seq[Elem[M]]]
  ): Html[M] =
    div(cls := "relative overflow-x-visible bg-neutral-primary-soft shadow-xs rounded-base border border-default")(
      table(cls := "w-full text-sm text-left rtl:text-right text-body")(
        thead(cls := "text-sm text-body bg-neutral-secondary-soft border-b rounded-base border-default")(
          tr(
            headers.map(header => th(scope := "col", cls := "px-6 py-3 font-medium")(text(header)))*
          )
        ),
        tbody(
          rows.map(row =>
            tr(cls := "bg-neutral-primary border-b border-default")(
              row.map(col => td(cls := "px-6 py-4")(col))*
            )
          )*
        )
      )
    )
