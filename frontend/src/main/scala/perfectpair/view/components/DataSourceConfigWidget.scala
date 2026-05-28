package perfectpair.view.components

import perfectpair.common.model.{CSVParserConfig, DataSourceConfig, S3ConnectionConfig}
import tyrian.Html.*
import tyrian.{EmptyAttribute, Html}

object DataSourceConfigWidget:

  def update(model: Model, msg: Msg): Model =
    msg match
      case Msg.Toggle(false)      => Model()
      case Msg.Toggle(true)       => model.copy(enabled = true)
      case Msg.SetHasHeader(v)    => model.copy(hasHeader = v)
      case Msg.AddColumn          => model.copy(columns = model.columns :+ "")
      case Msg.UpdateColumn(i, v) => model.copy(columns = model.columns.updated(i, v))
      case Msg.SetAmountCol(i)    => model.copy(amountColIndex = Some(i))
      case Msg.RemoveColumn(i) =>
        val newCols = model.columns.patch(i, Nil, 1)
        val newAmt = model.amountColIndex.flatMap { idx =>
          if idx == i then None
          else if idx > i then Some(idx - 1)
          else Some(idx)
        }
        model.copy(columns = newCols, amountColIndex = newAmt)
      case Msg.SetS3Bucket(v)   => model.copy(s3Bucket = v)
      case Msg.SetS3FilePath(v) => model.copy(s3FilePath = v)

  def toDataSourceConfig(m: Model): Option[DataSourceConfig] =
    if !m.enabled then None
    else
      Some(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = m.hasHeader,
            columnDefinitions = m.columns.zipWithIndex.map((name, i) => i -> name).toMap,
            amountField       = m.amountColIndex
          ),
          connectionConfig = S3ConnectionConfig(
            s3Bucket = m.s3Bucket,
            filePath = m.s3FilePath
          )
        )
      )

  def fromDataSourceConfig(cfg: Option[DataSourceConfig]): Model =
    cfg match
      case None => Model()
      case Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField), S3ConnectionConfig(bucket, path))) =>
        Model(
          enabled        = true,
          hasHeader      = hasHeader,
          columns        = colDefs.toList.sortBy(_._1).map(_._2),
          amountColIndex = amountField,
          s3Bucket       = bucket,
          s3FilePath     = path
        )

  def apply[M](model: Model, lift: Msg => M): Html[M] =
    div()(
      hr(cls := "my-4 border-md-outline-variant"),
      div(cls := "flex items-center justify-between mb-3")(
        span(cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant")(
          text("CSV Data Source")
        ),
        div(
          cls := "flex items-center gap-2 cursor-pointer",
          onClick(lift(Msg.Toggle(!model.enabled)))
        )(
          div(cls := s"w-9 h-5 rounded-full relative transition-colors ${
              if model.enabled then "bg-md-primary" else "bg-gray-300"
            }")(
            div(cls := s"w-4 h-4 bg-white rounded-full absolute top-0.5 shadow-sm transition-all ${
                if model.enabled then "right-0.5" else "left-0.5"
              }")()
          ),
          span(cls := s"text-xs font-medium ${if model.enabled then "text-md-primary" else "text-gray-400"}")(
            text(if model.enabled then "On" else "Off")
          )
        )
      ),
      if model.enabled then
        div()(
          div(
            cls := "flex items-center gap-2 mb-4 cursor-pointer",
            onClick(lift(Msg.SetHasHeader(!model.hasHeader)))
          )(
            div(cls := s"w-4 h-4 rounded border flex items-center justify-center ${
                if model.hasHeader then "bg-md-primary border-md-primary" else "border-gray-400"
              }")(
              if model.hasHeader then span(cls := "text-white text-xs leading-none")(text("✓")) else span()()
            ),
            span(cls := "text-sm text-md-on-surface")(text("Has Header Row"))
          ),
          if model.columns.nonEmpty then
            div(cls := "mb-3 border border-md-outline rounded overflow-hidden")(
              table(cls := "w-full text-xs border-collapse")(
                thead(cls := "bg-md-surface-variant")(
                  tr()(
                    th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium w-10")(text("Col")),
                    th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium")(text("Field Name")),
                    th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14")(text("Amount?")),
                    th(cls := "w-6")()
                  )
                ),
                tbody()(
                  model.columns.zipWithIndex.map { (colName, idx) =>
                    tr(cls := "border-t border-md-outline-variant")(
                      td(cls := "px-2 py-1.5 text-md-on-surface-variant font-medium")(text(idx.toString)),
                      td(cls := "px-2 py-1.5")(
                        input(
                          tpe := "text",
                          cls := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                          attribute("value", colName),
                          onChange(v => lift(Msg.UpdateColumn(idx, v)))
                        )
                      ),
                      td(cls := "text-center px-2 py-1.5")(
                        input(
                          tpe  := "radio",
                          name := "amountCol",
                          cls  := "accent-md-primary",
                          if model.amountColIndex.contains(idx) then attribute("checked", "checked")
                          else EmptyAttribute,
                          onChange(_ => lift(Msg.SetAmountCol(idx)))
                        )
                      ),
                      td(cls := "px-1 py-1.5 text-center")(
                        button(
                          tpe := "button",
                          cls := "text-gray-300 hover:text-md-error",
                          onClick(lift(Msg.RemoveColumn(idx)))
                        )(text("✕"))
                      )
                    )
                  }*
                )
              )
            )
          else div()(),
          button(
            tpe := "button",
            cls := "border border-dashed border-md-primary text-md-primary rounded px-3 py-1.5 text-xs hover:bg-md-primary-container transition-colors",
            onClick(lift(Msg.AddColumn))
          )(text("+ Add Column")),
          div(cls := "mt-4")(
            span(cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant")(
              text("S3 Connection")
            ),
            div(cls := "mt-2 flex flex-col gap-3")(
              div(cls := "relative border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
                label(
                  cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant"
                )(text("Bucket")),
                input(
                  tpe   := "text",
                  cls   := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-sm focus:outline-none rounded",
                  attribute("value", model.s3Bucket),
                  onChange(v => lift(Msg.SetS3Bucket(v)))
                )
              ),
              div(cls := "relative border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
                label(
                  cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant"
                )(text("File Path")),
                input(
                  tpe   := "text",
                  cls   := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-sm focus:outline-none rounded",
                  attribute("value", model.s3FilePath),
                  onChange(v => lift(Msg.SetS3FilePath(v)))
                )
              )
            )
          )
        )
      else div()()
    )

  case class Model(
    enabled:        Boolean      = false,
    hasHeader:      Boolean      = true,
    columns:        List[String] = List(),
    amountColIndex: Option[Int]  = None,
    s3Bucket:       String       = "",
    s3FilePath:     String       = ""
  )

  enum Msg:
    case Toggle(enabled: Boolean)
    case SetHasHeader(value: Boolean)
    case AddColumn
    case UpdateColumn(index: Int, name: String)
    case RemoveColumn(index: Int)
    case SetAmountCol(index: Int)
    case SetS3Bucket(value: String)
    case SetS3FilePath(value: String)
