package onparity.view.components

import com.raquo.laminar.api.L.*
import onparity.common.model.{
  BAIField,
  BAIParserConfig,
  CSVParserConfig,
  DataSourceConfig,
  JSONParserConfig,
  S3ConnectionConfig
}

object DataSourceConfigWidget:

  enum ParserType:
    case CSV, BAI, JSON

  // The user picks which CSV column positions to store (and under what name) rather than every
  // field implicitly getting stored in list order — columnIndex is typed explicitly (as text, so a
  // partially-edited/blank value doesn't get coerced mid-input) so a row for position 5 doesn't
  // require also defining rows for positions 0-4.
  case class CsvFieldRow(columnIndex: String = "", name: String = "")

  // JSONPath is arbitrary and user-defined (like CSV columns, unlike BAI2's fixed field set), so
  // this is a growable list of (path, stored name) pairs, the same shape as CSV's column list.
  case class JsonFieldRow(path: String = "", name: String = "")

  case class State(
    enabled:    Boolean    = false,
    parserType: ParserType = ParserType.CSV,
    hasHeader:  Boolean    = true,
    // csvAmountIndex is a position into csvFields (which row is the amount), not a CSV column
    // index itself — same indirection as jsonAmountIndex below, resolved to the real column index
    // only when building CSVParserConfig.
    csvFields:      List[CsvFieldRow] = List.empty,
    csvAmountIndex: Option[Int]       = None,
    // BAI2's fields are fixed and known (unlike CSV columns), so this is a name PER field rather
    // than a growable list — baiFieldsIncluded tracks which ones are turned on, and baiFieldNames
    // keeps every field's typed name (even while excluded) so toggling one back on doesn't lose it.
    baiFieldNames:     Map[BAIField, String] = BAIParserConfig.defaultFieldMappings,
    baiFieldsIncluded: Set[BAIField]         = BAIField.values.toSet,
    jsonFields:        List[JsonFieldRow]    = List.empty,
    jsonAmountIndex:   Option[Int]           = None,
    s3Bucket:          String                = "",
    s3FilePath:        String                = ""
  )

  object State:
    def fromConfig(cfg: Option[DataSourceConfig]): State = cfg match
      case None => State()
      case Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField), S3ConnectionConfig(bucket, path))) =>
        val rows = colDefs.toList.sortBy(_._1).map((idx, name) => CsvFieldRow(idx.toString, name))
        State(
          enabled        = true,
          parserType     = ParserType.CSV,
          hasHeader      = hasHeader,
          csvFields      = rows,
          csvAmountIndex = amountField.map(af => rows.indexWhere(_.columnIndex == af.toString)).filter(_ >= 0),
          s3Bucket       = bucket,
          s3FilePath     = path
        )
      case Some(DataSourceConfig(BAIParserConfig(fieldMappings), S3ConnectionConfig(bucket, path))) =>
        State(
          enabled           = true,
          parserType        = ParserType.BAI,
          baiFieldNames     = BAIParserConfig.defaultFieldMappings ++ fieldMappings,
          baiFieldsIncluded = fieldMappings.keySet,
          s3Bucket          = bucket,
          s3FilePath        = path
        )
      case Some(DataSourceConfig(JSONParserConfig(fieldMappings, amountPath), S3ConnectionConfig(bucket, path))) =>
        val rows = fieldMappings.toList.map((p, n) => JsonFieldRow(p, n))
        State(
          enabled         = true,
          parserType      = ParserType.JSON,
          jsonFields      = rows,
          jsonAmountIndex = amountPath.map(ap => rows.indexWhere(_.path == ap)).filter(_ >= 0),
          s3Bucket        = bucket,
          s3FilePath      = path
        )

    def toConfig(s: State): Option[DataSourceConfig] =
      if !s.enabled then None
      else
        val parserConfig = s.parserType match
          case ParserType.CSV =>
            CSVParserConfig(
              hasHeader = s.hasHeader,
              columnDefinitions = s.csvFields
                .flatMap(r => r.columnIndex.trim.toIntOption.map(idx => idx -> r.name))
                .toMap,
              amountField = s.csvAmountIndex.flatMap(s.csvFields.lift).flatMap(_.columnIndex.trim.toIntOption)
            )
          case ParserType.BAI =>
            BAIParserConfig(fieldMappings = s.baiFieldNames.filter((field, _) => s.baiFieldsIncluded.contains(field)))
          case ParserType.JSON =>
            JSONParserConfig(
              fieldMappings = s.jsonFields.filter(_.path.nonEmpty).map(r => r.path -> r.name).toMap,
              amountPath    = s.jsonAmountIndex.flatMap(s.jsonFields.lift).map(_.path).filter(_.nonEmpty)
            )
        Some(
          DataSourceConfig(
            parserConfig     = parserConfig,
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
        span(cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant", "Data Source"),
        div(
          cls := "flex items-center gap-2 cursor-pointer",
          onClick --> (_ => state.update(s => s.copy(enabled = !s.enabled))),
          div(
            cls <-- state.signal.map(s =>
              s"w-9 h-5 rounded-full relative transition-colors ${if s.enabled then "bg-md-primary" else "bg-gray-300"}"
            ),
            div(
              cls <-- state.signal.map(s => s"w-4 h-4 bg-white rounded-full absolute top-0.5 shadow-sm transition-all ${if s.enabled then "right-0.5" else "left-0.5"}")
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
        Form.selectField(
          "parserType",
          "File Format",
          Seq(
            ParserType.CSV.toString  -> "CSV",
            ParserType.BAI.toString  -> "BAI2",
            ParserType.JSON.toString -> "JSON (one record per line)"
          ),
          initialState.parserType.toString,
          v => state.update(_.copy(parserType = ParserType.valueOf(v)))
        ),
        // CSV-specific configuration: BAI2's field layout is a fixed standard, so none of this
        // (header row, column-to-field mapping) applies once BAI2 is selected.
        div(
          cls <-- state.signal.map(s => if !s.enabled || s.parserType != ParserType.CSV then "hidden" else ""),
          div(
            cls := "flex items-center gap-2 mb-4 cursor-pointer",
            onClick --> (_ => state.update(s => s.copy(hasHeader = !s.hasHeader))),
            div(
              cls <-- state.signal.map(s =>
                s"w-4 h-4 rounded border flex items-center justify-center ${
                    if s.hasHeader then "bg-md-primary border-md-primary" else "border-gray-400"
                  }"
              ),
              child <-- state.signal.map(s =>
                if s.hasHeader then span(cls := "text-white text-xs leading-none", "✓") else span()
              )
            ),
            span(cls := "text-sm text-md-on-surface dark:text-gray-200", "Has Header Row")
          ),
          div(
            cls <-- state.signal.map(s =>
              if s.csvFields.isEmpty then "hidden" else "mb-3 border border-md-outline rounded overflow-hidden"
            ),
            table(
              cls := "w-full text-xs border-collapse",
              thead(
                cls := "bg-md-surface-variant",
                tr(
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium w-20", "Column #"),
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "Field Name"),
                  th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14", "Amount?"),
                  th(cls := "w-6")
                )
              ),
              tbody(
                // splitByIndex reuses the tr DOM node at each index across state updates,
                // preserving input focus when typing
                children <-- state.signal.map(_.csvFields).splitByIndex { (idx, initialRow, rowSignal) =>
                  tr(
                    cls := "border-t border-md-outline-variant",
                    td(
                      cls := "px-2 py-1.5",
                      input(
                        typ          := "number",
                        cls          := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                        placeholder  := "0",
                        defaultValue := initialRow.columnIndex,
                        inContext(el => rowSignal --> Observer[CsvFieldRow](r => el.ref.value = r.columnIndex)),
                        onInput.mapToValue --> Observer[String](v =>
                          state.update(s =>
                            s.copy(csvFields = s.csvFields.updated(idx, s.csvFields(idx).copy(columnIndex = v)))
                          )
                        )
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      input(
                        typ          := "text",
                        cls          := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                        defaultValue := initialRow.name,
                        inContext(el => rowSignal --> Observer[CsvFieldRow](r => el.ref.value = r.name)),
                        onInput.mapToValue --> Observer[String](v =>
                          state
                            .update(s => s.copy(csvFields = s.csvFields.updated(idx, s.csvFields(idx).copy(name = v))))
                        )
                      )
                    ),
                    td(
                      cls := "text-center px-2 py-1.5",
                      input(
                        typ      := "radio",
                        nameAttr := "csvAmountField",
                        cls      := "accent-md-primary",
                        checked <-- state.signal.map(_.csvAmountIndex.contains(idx)),
                        onChange --> (_ => state.update(s => s.copy(csvAmountIndex = Some(idx))))
                      )
                    ),
                    td(
                      cls := "px-1 py-1.5 text-center",
                      button(
                        typ := "button",
                        cls := "text-gray-300 hover:text-md-error",
                        onClick --> (_ =>
                          state.update { s =>
                            val newFields = s.csvFields.patch(idx, Nil, 1)
                            val newAmt = s.csvAmountIndex.flatMap { i =>
                              if i == idx then None
                              else if i > idx then Some(i - 1)
                              else Some(i)
                            }
                            s.copy(csvFields = newFields, csvAmountIndex = newAmt)
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
            onClick --> (_ => state.update(s => s.copy(csvFields = s.csvFields :+ CsvFieldRow()))),
            "+ Add Field"
          )
        ),
        // BAI2-specific configuration: the field set is fixed and known, so this is a checkbox
        // (store this field?) plus an editable name per field, not a growable column list. Amount
        // has no row here — its position on every transaction record is always known, so it's
        // captured automatically rather than being something the user identifies.
        div(
          cls <-- state.signal.map(s => if !s.enabled || s.parserType != ParserType.BAI then "hidden" else ""),
          div(
            cls := "mb-2 text-xs text-md-on-surface-variant",
            "Amount is captured automatically from the BAI2 transaction record."
          ),
          div(
            cls := "mb-3 border border-md-outline rounded overflow-hidden",
            table(
              cls := "w-full text-xs border-collapse",
              thead(
                cls := "bg-md-surface-variant",
                tr(
                  th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14", "Include"),
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "BAI2 Field"),
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "Stored As")
                )
              ),
              tbody(
                BAIField.values.toSeq.map { field =>
                  tr(
                    cls := "border-t border-md-outline-variant",
                    td(
                      cls := "text-center px-2 py-1.5",
                      input(
                        typ := "checkbox",
                        cls := "accent-md-primary",
                        checked <-- state.signal.map(_.baiFieldsIncluded.contains(field)),
                        onClick --> (_ =>
                          state.update(s =>
                            s.copy(baiFieldsIncluded =
                              if s.baiFieldsIncluded.contains(field) then s.baiFieldsIncluded - field
                              else s.baiFieldsIncluded + field
                            )
                          )
                        )
                      )
                    ),
                    td(cls := "px-2 py-1.5 text-md-on-surface-variant", field.toString),
                    td(
                      cls := "px-2 py-1.5",
                      input(
                        typ := "text",
                        cls := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                        defaultValue := initialState.baiFieldNames
                          .getOrElse(field, BAIParserConfig.defaultFieldMappings(field)),
                        onInput.mapToValue --> Observer[String](v =>
                          state.update(s => s.copy(baiFieldNames = s.baiFieldNames.updated(field, v)))
                        )
                      )
                    )
                  )
                }*
              )
            )
          )
        ),
        // JSON-specific configuration: one standalone JSON document per file row. Fields are
        // arbitrary and user-defined (like CSV columns, unlike BAI2's fixed set), identified by a
        // JSONPath expression rather than a column index — same growable-list shape as CSV's table.
        div(
          cls <-- state.signal.map(s => if !s.enabled || s.parserType != ParserType.JSON then "hidden" else ""),
          div(
            cls <-- state.signal.map(s =>
              if s.jsonFields.isEmpty then "hidden" else "mb-3 border border-md-outline rounded overflow-hidden"
            ),
            table(
              cls := "w-full text-xs border-collapse",
              thead(
                cls := "bg-md-surface-variant",
                tr(
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "JSON Path"),
                  th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium", "Field Name"),
                  th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14", "Amount?"),
                  th(cls := "w-6")
                )
              ),
              tbody(
                // splitByIndex reuses the tr DOM node at each index across state updates,
                // preserving input focus when typing
                children <-- state.signal.map(_.jsonFields).splitByIndex { (idx, initialRow, rowSignal) =>
                  tr(
                    cls := "border-t border-md-outline-variant",
                    td(
                      cls := "px-2 py-1.5",
                      input(
                        typ          := "text",
                        cls          := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                        placeholder  := "$.path.to.field",
                        defaultValue := initialRow.path,
                        inContext(el => rowSignal --> Observer[JsonFieldRow](r => el.ref.value = r.path)),
                        onInput.mapToValue --> Observer[String](v =>
                          state.update(s =>
                            s.copy(jsonFields = s.jsonFields.updated(idx, s.jsonFields(idx).copy(path = v)))
                          )
                        )
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      input(
                        typ          := "text",
                        cls          := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                        defaultValue := initialRow.name,
                        inContext(el => rowSignal --> Observer[JsonFieldRow](r => el.ref.value = r.name)),
                        onInput.mapToValue --> Observer[String](v =>
                          state.update(s =>
                            s.copy(jsonFields = s.jsonFields.updated(idx, s.jsonFields(idx).copy(name = v)))
                          )
                        )
                      )
                    ),
                    td(
                      cls := "text-center px-2 py-1.5",
                      input(
                        typ      := "radio",
                        nameAttr := "jsonAmountField",
                        cls      := "accent-md-primary",
                        checked <-- state.signal.map(_.jsonAmountIndex.contains(idx)),
                        onChange --> (_ => state.update(s => s.copy(jsonAmountIndex = Some(idx))))
                      )
                    ),
                    td(
                      cls := "px-1 py-1.5 text-center",
                      button(
                        typ := "button",
                        cls := "text-gray-300 hover:text-md-error",
                        onClick --> (_ =>
                          state.update { s =>
                            val newFields = s.jsonFields.patch(idx, Nil, 1)
                            val newAmt = s.jsonAmountIndex.flatMap { i =>
                              if i == idx then None
                              else if i > idx then Some(i - 1)
                              else Some(i)
                            }
                            s.copy(jsonFields = newFields, jsonAmountIndex = newAmt)
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
            onClick --> (_ => state.update(s => s.copy(jsonFields = s.jsonFields :+ JsonFieldRow()))),
            "+ Add Field"
          )
        ),
        div(
          cls := "mt-4",
          span(
            cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant",
            "S3 Connection"
          ),
          div(
            cls := "mt-2",
            Form.field("s3Bucket", "Bucket", initialState.s3Bucket, v => state.update(_.copy(s3Bucket = v))),
            Form.field("s3FilePath", "File Path", initialState.s3FilePath, v => state.update(_.copy(s3FilePath = v)))
          )
        )
      )
    )

    (el, state.signal.map(State.toConfig))
