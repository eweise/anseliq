package perfectpair.view.components

import com.raquo.laminar.api.L.*

object Table:
  def create(headers: Seq[String], rows: Seq[Seq[HtmlElement]], numCols: Set[Int] = Set.empty): HtmlElement =
    def cellCls(i: Int, base: String): String =
      if numCols.contains(i) then s"$base text-right num" else base

    div(
      cls := "relative overflow-x-auto bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-md-outline dark:border-gray-700",
      table(
        cls := "w-full text-sm text-left rtl:text-right text-md-on-surface dark:text-gray-200",
        thead(
          cls := "text-xs uppercase text-md-on-surface-variant dark:text-gray-400 bg-md-surface-alt dark:bg-md-surface-alt-dark border-b border-md-outline dark:border-gray-700",
          tr(
            headers.zipWithIndex.map((h, i) => th(cls := cellCls(i, "px-6 py-3 font-medium"), h))*
          )
        ),
        tbody(
          rows.map(row =>
            tr(cls := "bg-white dark:bg-gray-800 border-b border-md-outline dark:border-gray-700 last:border-b-0").amend(
              row.zipWithIndex.map((col, i) => td(cls := cellCls(i, "px-6 py-4 align-top"), col))*
            )
          )*
        )
      )
    )
