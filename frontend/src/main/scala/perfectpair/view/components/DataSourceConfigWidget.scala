package perfectpair.view.components

import com.raquo.laminar.api.L.*
import perfectpair.common.model.{CSVParserConfig, DataSourceConfig, S3ConnectionConfig}

object DataSourceConfigWidget:

  case class State(
    enabled:        Boolean      = false,
    hasHeader:      Boolean      = true,
    columns:        List[String] = List.empty,
    amountColIndex: Option[Int]  = None,
    s3Bucket:       String       = "",
    s3FilePath:     String       = ""
  )

  object State:
    def fromConfig(cfg: Option[DataSourceConfig]): State = cfg match
      case None => State()
      case Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField), S3ConnectionConfig(bucket, path))) =>
        State(
          enabled        = true,
          hasHeader      = hasHeader,
          columns        = colDefs.toList.sortBy(_._1).map(_._2),
          amountColIndex = amountField,
          s3Bucket       = bucket,
          s3FilePath     = path
        )

    def toConfig(s: State): Option[DataSourceConfig] =
      if !s.enabled then None
      else
        Some(
          DataSourceConfig(
            parserConfig = CSVParserConfig(
              hasHeader         = s.hasHeader,
              columnDefinitions = s.columns.zipWithIndex.map((name, i) => i -> name).toMap,
              amountField       = s.amountColIndex
            ),
            connectionConfig = S3ConnectionConfig(s3Bucket = s.s3Bucket, filePath = s.s3FilePath)
          )
        )

  def apply(initial: Option[DataSourceConfig]): (HtmlElement, Signal[Option[DataSourceConfig]]) =
    val initialState = State.fromConfig(initial)
    val state        = Var(initialState)

    val el = div(
      hr(cls := "my-4 border-md-outline-variant"),
      div(
        cls := "flex items-center justify-between mb-3",
        span(cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant", "CSV Data Source"),
        div(
          cls := "flex items-center gap-2 cursor-pointer",
          onClick --> (_ => state.update(s => s.copy(enabled = !s.enabled))),
          div(
            cls <-- state.signal.map(s =>
              s"w-9 h-5 rounded-full relative transition-colors ${if s.enabled then "bg-md-primary" else "bg-gray-300"}"
            ),
            div(
              cls <-- state.signal.map(s =>
                s"w-4 h-4 bg-white rounded-full absolute top-0.5 shadow-sm transition-all ${if s.enabled then "right-0.5" else "left-0.5"}"
              )
            )
          ),
          span(
            cls <-- state.signal.map(s =>
              s"text-xs font-medium ${if s.enabled then "text-md-primary" else "text-gray-400"}"
            ),
            child.text <-- state.signal.map(s => if s.enabled then "On" else "Off")
          )
        )
      ),
      // Use CSS show/hide so inputs are never destroyed when state changes
      div(
        cls <-- state.signal.map(s => if !s.enabled then "hidden" else ""),
        div(
          cls := "flex items-center gap-2 mb-4 cursor-pointer",
          onClick --> (_ => state.update(s => s.copy(hasHeader = !s.hasHeader))),
          div(
            cls <-- state.signal.map(s =>
              s"w-4 h-4 rounded border flex items-center justify-center ${if s.hasHeader then "bg-md-primary border-md-primary" else "border-gray-400"}"
            ),
            child <-- state.signal.map(s =>
              if s.hasHeader then span(cls := "text-white text-xs leading-none", "✓") else span()
            )
          ),
          span(cls := "text-sm text-md-on-surface dark:text-gray-200", "Has Header Row")
        ),
        div(
          cls <-- state.signal.map(s =>
            if s.columns.isEmpty then "hidden" else "mb-3 border border-md-outline rounded overflow-hidden"
          ),
          table(
            cls := "w-full text-xs border-collapse",
            thead(
              cls := "bg-md-surface-variant",
              tr(
                th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium w-10", "Col"),
                th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "Field Name"),
                th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14", "Amount?"),
                th(cls := "w-6")
              )
            ),
            tbody(
              // splitByIndex reuses the tr DOM node at each index across state updates,
              // preserving input focus when typing
              children <-- state.signal.map(_.columns).splitByIndex { (idx, initialColName, colSignal) =>
                tr(
                  cls := "border-t border-md-outline-variant",
                  td(cls := "px-2 py-1.5 text-md-on-surface-variant font-medium", idx.toString),
                  td(
                    cls := "px-2 py-1.5",
                    input(
                      typ          := "text",
                      cls          := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                      defaultValue := initialColName,
                      inContext(el => colSignal --> Observer[String](v => el.ref.value = v)),
                      onInput.mapToValue --> Observer[String](v =>
                        state.update(s => s.copy(columns = s.columns.updated(idx, v)))
                      )
                    )
                  ),
                  td(
                    cls := "text-center px-2 py-1.5",
                    input(
                      typ      := "radio",
                      nameAttr := "amountCol",
                      cls      := "accent-md-primary",
                      checked <-- state.signal.map(_.amountColIndex.contains(idx)),
                      onChange --> (_ => state.update(s => s.copy(amountColIndex = Some(idx))))
                    )
                  ),
                  td(
                    cls := "px-1 py-1.5 text-center",
                    button(
                      typ := "button",
                      cls := "text-gray-300 hover:text-md-error",
                      onClick --> (_ =>
                        state.update { s =>
                          val newCols = s.columns.patch(idx, Nil, 1)
                          val newAmt = s.amountColIndex.flatMap { i =>
                            if i == idx then None
                            else if i > idx then Some(i - 1)
                            else Some(i)
                          }
                          s.copy(columns = newCols, amountColIndex = newAmt)
                        }
                      ),
                      "✕"
                    )
                  )
                )
              }
            )
          )
        ),
        button(
          typ := "button",
          cls := "border border-dashed border-md-primary text-md-primary rounded px-3 py-1.5 text-xs hover:bg-md-primary-container transition-colors",
          onClick --> (_ => state.update(s => s.copy(columns = s.columns :+ ""))),
          "+ Add Column"
        ),
        div(
          cls := "mt-4",
          span(
            cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant",
            "S3 Connection"
          ),
          div(
            cls := "mt-2 flex flex-col gap-3",
            div(
              cls := "relative border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
              label(
                cls := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
                "Bucket"
              ),
              input(
                typ          := "text",
                cls          := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-sm focus:outline-none rounded",
                defaultValue := initialState.s3Bucket,
                onInput.mapToValue --> Observer[String](v => state.update(_.copy(s3Bucket = v)))
              )
            ),
            div(
              cls := "relative border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
              label(
                cls := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
                "File Path"
              ),
              input(
                typ          := "text",
                cls          := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-sm focus:outline-none rounded",
                defaultValue := initialState.s3FilePath,
                onInput.mapToValue --> Observer[String](v => state.update(_.copy(s3FilePath = v)))
              )
            )
          )
        )
      )
    )

    (el, state.signal.map(State.toConfig))
