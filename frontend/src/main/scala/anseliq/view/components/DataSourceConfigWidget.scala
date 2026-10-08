package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.common.model.{
  BaiField,
  BaiParserConfig,
  CsvParserConfig,
  DataSourceConfig,
  FixedWidthField,
  FixedWidthParserConfig,
  JsonParserConfig,
  S3ConnectionConfig
}

object DataSourceConfigWidget:

  enum ParserType:
    case CSV, BAI, JSON, FixedWidth

  // The user picks which CSV column positions to store (and under what name) rather than every
  // field implicitly getting stored in list order — columnIndex is typed explicitly (as text, so a
  // partially-edited/blank value doesn't get coerced mid-input) so a row for position 5 doesn't
  // require also defining rows for positions 0-4.
  case class CsvFieldRow(columnIndex: String = "", name: String = "")

  // JSONPath is arbitrary and user-defined (like CSV columns, unlike BAI2's fixed field set), so
  // this is a growable list of (path, stored name) pairs, the same shape as CSV's column list.
  case class JsonFieldRow(path: String = "", name: String = "")

  // Start/end positions are typed as text, same reasoning as CsvFieldRow's columnIndex — a
  // partially-edited value shouldn't get coerced mid-input. Unlike CSV/JSON, the domain model
  // (FixedWidthField) stores isAmount directly on each field rather than a separate index pointer,
  // but the widget still tracks it as a single-selection index (fixedWidthAmountIndex on State)
  // for the same reason CSV/JSON do: it's a radio-button selection over a growable list of rows.
  case class FixedWidthFieldRow(name: String = "", startPosition: String = "", endPosition: String = "")

  case class State(
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
                    baiFieldNames:         Map[BaiField, String]    = BaiParserConfig.defaultFieldMappings,
                    baiFieldsIncluded:     Set[BaiField]            = BaiField.values.toSet,
                    jsonFields:            List[JsonFieldRow]       = List.empty,
                    jsonAmountIndex:       Option[Int]              = None,
                    fixedWidthFields:      List[FixedWidthFieldRow] = List.empty,
                    fixedWidthAmountIndex: Option[Int]              = None,
                    s3Bucket:              String                   = "",
                    s3FilePath:            String                   = ""
  )

  object State:
    def fromConfig(cfg: Option[DataSourceConfig]): State = cfg match
      case None => State()
      case Some(DataSourceConfig(CsvParserConfig(hasHeader, colDefs, amountField), S3ConnectionConfig(bucket, path))) =>
        val rows = colDefs.toList.sortBy(_._1).map((idx, name) => CsvFieldRow(idx.toString, name))
        State(
          parserType     = ParserType.CSV,
          hasHeader      = hasHeader,
          csvFields      = rows,
          csvAmountIndex = amountField.map(af => rows.indexWhere(_.columnIndex == af.toString)).filter(_ >= 0),
          s3Bucket       = bucket,
          s3FilePath     = path
        )
      case Some(DataSourceConfig(BaiParserConfig(fieldMappings), S3ConnectionConfig(bucket, path))) =>
        State(
          parserType        = ParserType.BAI,
          baiFieldNames     = BaiParserConfig.defaultFieldMappings ++ fieldMappings,
          baiFieldsIncluded = fieldMappings.keySet,
          s3Bucket          = bucket,
          s3FilePath        = path
        )
      case Some(DataSourceConfig(JsonParserConfig(fieldMappings, amountPath), S3ConnectionConfig(bucket, path))) =>
        val rows = fieldMappings.toList.map((p, n) => JsonFieldRow(p, n))
        State(
          parserType      = ParserType.JSON,
          jsonFields      = rows,
          jsonAmountIndex = amountPath.map(ap => rows.indexWhere(_.path == ap)).filter(_ >= 0),
          s3Bucket        = bucket,
          s3FilePath      = path
        )
      case Some(DataSourceConfig(FixedWidthParserConfig(hasHeader, fields), S3ConnectionConfig(bucket, path))) =>
        val rows = fields.toList.map(f => FixedWidthFieldRow(f.name, f.startPosition.toString, f.endPosition.toString))
        State(
          parserType            = ParserType.FixedWidth,
          hasHeader             = hasHeader,
          fixedWidthFields      = rows,
          fixedWidthAmountIndex = fields.zipWithIndex.collectFirst { case (f, i) if f.isAmount => i },
          s3Bucket              = bucket,
          s3FilePath            = path
        )

    def toConfig(s: State): Option[DataSourceConfig] =
      val parserConfig = s.parserType match
        case ParserType.CSV =>
          CsvParserConfig(
            hasHeader = s.hasHeader,
            columnDefinitions = s.csvFields
              .flatMap(r => r.columnIndex.trim.toIntOption.map(idx => idx -> r.name))
              .toMap,
            amountField = s.csvAmountIndex.flatMap(s.csvFields.lift).flatMap(_.columnIndex.trim.toIntOption)
          )
        case ParserType.BAI =>
          BaiParserConfig(fieldMappings = s.baiFieldNames.filter((field, _) => s.baiFieldsIncluded.contains(field)))
        case ParserType.JSON =>
          JsonParserConfig(
            fieldMappings = s.jsonFields.filter(_.path.nonEmpty).map(r => r.path -> r.name).toMap,
            amountPath    = s.jsonAmountIndex.flatMap(s.jsonFields.lift).map(_.path).filter(_.nonEmpty)
          )
        case ParserType.FixedWidth =>
          FixedWidthParserConfig(
            hasHeader = s.hasHeader,
            fields = s.fixedWidthFields.zipWithIndex.flatMap { (r, idx) =>
              for
                start <- r.startPosition.trim.toIntOption
                end   <- r.endPosition.trim.toIntOption
              yield FixedWidthField(r.name, start, end, isAmount = s.fixedWidthAmountIndex.contains(idx))
            }
          )
      Some(
        DataSourceConfig(
          parserConfig     = parserConfig,
          connectionConfig = S3ConnectionConfig(s3Bucket = s.s3Bucket, filePath = s.s3FilePath)
        )
      )

  // Third element lets the caller gate its Save button on the same validation rules
  // (DataSourceConfig.validate, in common) that also run again on the backend as a safety net.
  def apply(initial: Option[DataSourceConfig]): (HtmlElement, Signal[Option[DataSourceConfig]], Signal[List[String]]) =
    val initialState = State.fromConfig(initial)
    val state        = Var(initialState)
    val configSignal = state.signal.map(State.toConfig)
    val errorsSignal = configSignal.map(_.map(_.validate).getOrElse(Nil))

    val el = div(
      hr(cls := "my-4 border-md-outline-variant"),
      div(
        cls := "mb-3",
        span(cls := Form.sectionLabelCls, "Data Source")
      ),
      div(
        Form.selectField(
          "parserType",
          "File Format",
          Seq(
            ParserType.CSV.toString        -> "CSV",
            ParserType.BAI.toString        -> "BAI2",
            ParserType.JSON.toString       -> "JSON (one record per line)",
            ParserType.FixedWidth.toString -> "Fixed Width"
          ),
          initialState.parserType.toString,
          v => state.update(_.copy(parserType = ParserType.valueOf(v)))
        ),
        // CSV-specific configuration: BAI2's field layout is a fixed standard, so none of this
        // (header row, column-to-field mapping) applies once BAI2 is selected.
        div(
          cls <-- state.signal.map(s => if s.parserType != ParserType.CSV then "hidden" else ""),
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
              if s.csvFields.isEmpty then "hidden"
              else s"mb-3 ${Form.tableContainerCls}"
            ),
            table(
              cls := Form.tableCls,
              thead(
                cls := Form.tableHeadCls,
                tr(
                  th(
                    cls := s"text-left px-2 py-2 w-20 ${Form.tableHeaderCellCls}",
                    "Column #"
                  ),
                  th(
                    cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}",
                    "Field Name"
                  ),
                  th(
                    cls := s"text-center px-2 py-2 w-14 ${Form.tableHeaderCellCls}",
                    "Amount?"
                  ),
                  th(cls := "w-6")
                )
              ),
              tbody(
                // splitByIndex reuses the tr DOM node at each index across state updates,
                // preserving input focus when typing
                children <-- state.signal.map(_.csvFields).splitByIndex { (idx, initialRow, rowSignal) =>
                  tr(
                    cls := Form.tableRowCls,
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "number",
                        initialRow.columnIndex,
                        v =>
                          state.update(s =>
                            s.copy(csvFields = s.csvFields.updated(idx, s.csvFields(idx).copy(columnIndex = v)))
                          ),
                        placeholderText = "0",
                        updates         = rowSignal.map(_.columnIndex).changes
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "text",
                        initialRow.name,
                        v =>
                          state
                            .update(s => s.copy(csvFields = s.csvFields.updated(idx, s.csvFields(idx).copy(name = v)))),
                        updates = rowSignal.map(_.name).changes
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
          cls <-- state.signal.map(s => if s.parserType != ParserType.BAI then "hidden" else ""),
          div(
            cls := s"mb-2 text-xs ${Form.mutedTextCls}",
            "Amount is captured automatically from the BAI2 transaction record."
          ),
          div(
            cls := s"mb-3 ${Form.tableContainerCls}",
            table(
              cls := Form.tableCls,
              thead(
                cls := Form.tableHeadCls,
                tr(
                  th(
                    cls := s"text-center px-2 py-2 w-14 ${Form.tableHeaderCellCls}",
                    "Include"
                  ),
                  th(
                    cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}",
                    "BAI2 Field"
                  ),
                  th(
                    cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}",
                    "Stored As"
                  )
                )
              ),
              tbody(
                BaiField.values.toSeq.map { field =>
                  tr(
                    cls := Form.tableRowCls,
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
                    td(cls := s"px-2 py-1.5 ${Form.mutedTextCls}", field.toString),
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "text",
                        initialState.baiFieldNames.getOrElse(field, BaiParserConfig.defaultFieldMappings(field)),
                        v => state.update(s => s.copy(baiFieldNames = s.baiFieldNames.updated(field, v)))
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
          cls <-- state.signal.map(s => if s.parserType != ParserType.JSON then "hidden" else ""),
          div(
            cls <-- state.signal.map(s =>
              if s.jsonFields.isEmpty then "hidden"
              else s"mb-3 ${Form.tableContainerCls}"
            ),
            table(
              cls := Form.tableCls,
              thead(
                cls := Form.tableHeadCls,
                tr(
                  th(
                    cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}",
                    "JSON Path"
                  ),
                  th(
                    cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}",
                    "Field Name"
                  ),
                  th(
                    cls := s"text-center px-2 py-2 w-14 ${Form.tableHeaderCellCls}",
                    "Amount?"
                  ),
                  th(cls := "w-6")
                )
              ),
              tbody(
                // splitByIndex reuses the tr DOM node at each index across state updates,
                // preserving input focus when typing
                children <-- state.signal.map(_.jsonFields).splitByIndex { (idx, initialRow, rowSignal) =>
                  tr(
                    cls := Form.tableRowCls,
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "text",
                        initialRow.path,
                        v =>
                          state.update(s =>
                            s.copy(jsonFields = s.jsonFields.updated(idx, s.jsonFields(idx).copy(path = v)))
                          ),
                        placeholderText = "$.path.to.field",
                        updates         = rowSignal.map(_.path).changes
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "text",
                        initialRow.name,
                        v =>
                          state.update(s =>
                            s.copy(jsonFields = s.jsonFields.updated(idx, s.jsonFields(idx).copy(name = v)))
                          ),
                        updates = rowSignal.map(_.name).changes
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
        // Fixed-width-specific configuration: every line has the same field layout at fixed
        // character positions. Positions are shown/entered 1-based and inclusive, the convention
        // real-world fixed-width/mainframe file specs use, not CSV's 0-indexed columns.
        div(
          cls <-- state.signal.map(s => if s.parserType != ParserType.FixedWidth then "hidden" else ""),
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
            cls := s"mb-2 text-xs ${Form.mutedTextCls}",
            "Positions are 1-based and inclusive (e.g. Start 1, End 10 covers the first 10 characters)."
          ),
          div(
            cls <-- state.signal.map(s =>
              if s.fixedWidthFields.isEmpty then "hidden"
              else s"mb-3 ${Form.tableContainerCls}"
            ),
            table(
              cls := Form.tableCls,
              thead(
                cls := Form.tableHeadCls,
                tr(
                  th(cls := s"text-left px-2 py-2 w-16 ${Form.tableHeaderCellCls}", "Start"),
                  th(cls := s"text-left px-2 py-2 w-16 ${Form.tableHeaderCellCls}", "End"),
                  th(cls := s"text-left px-2 py-2 ${Form.tableHeaderCellCls}", "Field Name"),
                  th(cls := s"text-center px-2 py-2 w-14 ${Form.tableHeaderCellCls}", "Amount?"),
                  th(cls := "w-6")
                )
              ),
              tbody(
                // splitByIndex reuses the tr DOM node at each index across state updates,
                // preserving input focus when typing
                children <-- state.signal.map(_.fixedWidthFields).splitByIndex { (idx, initialRow, rowSignal) =>
                  tr(
                    cls := Form.tableRowCls,
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "number",
                        initialRow.startPosition,
                        v =>
                          state.update(s =>
                            s.copy(fixedWidthFields =
                              s.fixedWidthFields.updated(idx, s.fixedWidthFields(idx).copy(startPosition = v))
                            )
                          ),
                        placeholderText = "1",
                        updates         = rowSignal.map(_.startPosition).changes
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "number",
                        initialRow.endPosition,
                        v =>
                          state.update(s =>
                            s.copy(fixedWidthFields =
                              s.fixedWidthFields.updated(idx, s.fixedWidthFields(idx).copy(endPosition = v))
                            )
                          ),
                        placeholderText = "10",
                        updates         = rowSignal.map(_.endPosition).changes
                      )
                    ),
                    td(
                      cls := "px-2 py-1.5",
                      Form.tableInput(
                        "text",
                        initialRow.name,
                        v =>
                          state.update(s =>
                            s.copy(fixedWidthFields =
                              s.fixedWidthFields.updated(idx, s.fixedWidthFields(idx).copy(name = v))
                            )
                          ),
                        updates = rowSignal.map(_.name).changes
                      )
                    ),
                    td(
                      cls := "text-center px-2 py-1.5",
                      input(
                        typ      := "radio",
                        nameAttr := "fixedWidthAmountField",
                        cls      := "accent-md-primary",
                        checked <-- state.signal.map(_.fixedWidthAmountIndex.contains(idx)),
                        onChange --> (_ => state.update(s => s.copy(fixedWidthAmountIndex = Some(idx))))
                      )
                    ),
                    td(
                      cls := "px-1 py-1.5 text-center",
                      button(
                        typ := "button",
                        cls := "text-gray-300 hover:text-md-error",
                        onClick --> (_ =>
                          state.update { s =>
                            val newFields = s.fixedWidthFields.patch(idx, Nil, 1)
                            val newAmt = s.fixedWidthAmountIndex.flatMap { i =>
                              if i == idx then None
                              else if i > idx then Some(i - 1)
                              else Some(i)
                            }
                            s.copy(fixedWidthFields = newFields, fixedWidthAmountIndex = newAmt)
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
            onClick --> (_ => state.update(s => s.copy(fixedWidthFields = s.fixedWidthFields :+ FixedWidthFieldRow()))),
            "+ Add Field"
          )
        ),
        child.maybe <-- errorsSignal.map { errors =>
          if errors.isEmpty then None
          else
            Some(
              div(
                cls := "mt-3 border border-md-error rounded p-3 text-xs text-md-error",
                ul(cls := "list-disc pl-4 space-y-0.5", errors.map(e => li(e)))
              )
            )
        },
        div(
          cls := "mt-4",
          span(cls := Form.sectionLabelCls, "S3 Connection"),
          div(
            cls := "mt-2",
            Form.field("s3Bucket", "Bucket", initialState.s3Bucket, v => state.update(_.copy(s3Bucket = v))),
            Form.field("s3FilePath", "File Path", initialState.s3FilePath, v => state.update(_.copy(s3FilePath = v)))
          )
        )
      )
    )

    (el, configSignal, errorsSignal)
