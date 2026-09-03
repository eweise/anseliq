package onparity.view.components

import com.raquo.laminar.api.L.*

object Table:
  private enum SortDir:
    case Asc, Desc

  def create(
    headers:       Seq[String],
    rows:          Seq[Seq[HtmlElement]],
    numCols:       Set[Int] = Set.empty,
    hoverableRows: Boolean  = false
  ): HtmlElement =
    def cellCls(i: Int, base: String): String =
      if numCols.contains(i) then s"$base text-right num" else base

    // Sort state is purely a display-order concern (which already-built row reorders on top,
    // reversed on a second click of the same column) — no data refetch, no signature change needed
    // at any of this component's many call sites. The sort key is each cell's own rendered text,
    // parsed as a number for columns already flagged numeric via numCols.
    val sortState = Var(Option.empty[(Int, SortDir)])

    def cellText(row: Seq[HtmlElement], col: Int): String =
      row.lift(col).map(_.ref.textContent).getOrElse("")

    def compareRows(a: Seq[HtmlElement], b: Seq[HtmlElement], col: Int): Int =
      val ka = cellText(a, col)
      val kb = cellText(b, col)
      if numCols.contains(col) then
        val na = ka.replaceAll("[^0-9.\\-]", "").toDoubleOption.getOrElse(Double.NegativeInfinity)
        val nb = kb.replaceAll("[^0-9.\\-]", "").toDoubleOption.getOrElse(Double.NegativeInfinity)
        na.compareTo(nb)
      else ka.compareToIgnoreCase(kb)

    def sortedRows(state: Option[(Int, SortDir)]): Seq[Seq[HtmlElement]] =
      state match
        case None => rows
        case Some((col, dir)) =>
          val ascending = rows.sortWith(compareRows(_, _, col) < 0)
          if dir == SortDir.Desc then ascending.reverse else ascending

    def headerCell(h: String, i: Int): HtmlElement =
      if h.isEmpty then th(cls := cellCls(i, "px-6 py-3 font-medium"), h)
      else
        th(
          cls := cellCls(
            i,
            "px-6 py-3 font-medium cursor-pointer select-none hover:text-md-on-surface dark:hover:text-white"
          ),
          onClick --> { _ =>
            sortState.update {
              case Some((c, SortDir.Asc)) if c == i  => Some((i, SortDir.Desc))
              case Some((c, SortDir.Desc)) if c == i => Some((i, SortDir.Asc))
              case _                                 => Some((i, SortDir.Asc))
            }
          },
          span(h),
          child.maybe <-- sortState.signal.map {
            case Some((c, dir)) if c == i => Some(span(cls := "ml-1", if dir == SortDir.Asc then "▲" else "▼"))
            case _                        => None
          }
        )

    div(
      cls := s"relative overflow-x-auto ${Card.cls}",
      table(
        cls := "w-full text-sm text-left rtl:text-right text-md-on-surface dark:text-gray-200",
        thead(
          cls := "text-xs uppercase text-md-on-surface-variant dark:text-gray-400 bg-md-surface-alt dark:bg-md-surface-alt-dark border-b border-md-outline dark:border-gray-700",
          tr(headers.zipWithIndex.map(headerCell)*)
        ),
        tbody(
          children <-- sortState.signal.map { state =>
            val rowCls =
              if hoverableRows then
                "bg-white dark:bg-gray-800 border-b border-md-outline dark:border-gray-700 last:border-b-0 hover:bg-purple-100 dark:hover:bg-purple-900 transition-colors"
              else "bg-white dark:bg-gray-800 border-b border-md-outline dark:border-gray-700 last:border-b-0"
            sortedRows(state).map(row =>
              tr(cls := rowCls)
                .amend(
                  row.zipWithIndex.map((col, i) => td(cls := cellCls(i, "px-6 py-4 align-top"), col))*
                )
            )
          }
        )
      )
    )
