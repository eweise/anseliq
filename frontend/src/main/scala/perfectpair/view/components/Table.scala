package perfectpair.view.components

import com.raquo.laminar.api.L.*

object Table:
  def create(headers: Seq[String], rows: Seq[Seq[HtmlElement]]): HtmlElement =
    div(cls := "relative overflow-x-visible bg-neutral-primary-soft shadow-xs rounded-base border border-default",
      table(cls := "w-full text-sm text-left rtl:text-right text-body",
        thead(cls := "text-sm text-body bg-neutral-secondary-soft border-b rounded-base border-default",
          tr(
            headers.map(h => th(cls := "px-6 py-3 font-medium", h))*
          )
        ),
        tbody(
          rows.map(row =>
            tr(cls := "bg-neutral-primary border-b border-default").amend(
              row.map(col => td(cls := "px-6 py-4", col))*
            )
          )*
        )
      )
    )
