package anseliq.view.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.{FileReader, HTMLInputElement}
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  BaiParserConfig,
  CsvParserConfig,
  FileFeedPayload,
  FixedWidthParserConfig,
  JsonParserConfig,
  ParseTestResultPayload,
  ParsedRowPayload,
  ParserConfig
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{Buttons, Card, PageHeader, Table}

object TestParserPage:

  private val maxLines = 1000

  enum UploadStatus:
    case Idle, Reading, Uploading, Done
    case Failed(msg: String)

  case class State(
    fileFeed: Option[FileFeedPayload]        = None,
    result:   Option[ParseTestResultPayload] = None,
    status:   UploadStatus                   = UploadStatus.Idle
  )

  def apply(fileFeedId: String): HtmlElement =
    val state       = Var(State())
    val fileReadBus = EventBus[String]()

    PageHeader.page(
      ApiClient
        .get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        .apiBind(feeds => state.update(_.copy(fileFeed = feeds.find(_.id.contains(fileFeedId))))),
      fileReadBus.events.flatMapSwitch { content =>
        state.update(_.copy(status = UploadStatus.Uploading))
        ApiClient.postRawExpectJson[ParseTestResultPayload](
          HttpPath.url(s"${HttpPath.fileFeed}/$fileFeedId/test-parse"),
          content,
          SessionStore.token
        )
      } --> {
        case Right(result) => state.update(_.copy(result = Some(result), status = UploadStatus.Done))
        case Left(e)       => state.update(_.copy(status = UploadStatus.Failed(e)))
      },
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.back())),
        div(
          cls := "flex justify-between items-start",
          PageHeader.title(
            child.text <-- state.signal.map(s => s"Test Parser — ${s.fileFeed.map(_.name).getOrElse(fileFeedId)}")
          ),
          child.maybe <-- state.signal.map(_.fileFeed.filter(_ => SessionStore.canEdit).map(editParserButton))
        )
      ),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map(s => s.fileFeed.map(specConfigSection).getOrElse(div())),
        uploadSection(state, fileReadBus),
        child <-- state.signal.map(resultSection)
      )
    )

  private def editParserButton(feed: FileFeedPayload): HtmlElement =
    Buttons.linkButton("Edit Parser", Observer[Unit](_ => Nav.toMaintainFileFeed(feed.id.getOrElse("new"))))

  // Shows what the feed's parser actually expects, so the user knows what kind of file to upload
  // (which columns/fields, in what format) instead of guessing from the feed's name alone.
  private def specConfigSection(feed: FileFeedPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-4 max-w-lg",
      h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "Parser Configuration"),
      feed.dataSourceConfig match
        case None           => div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "No parser configured")
        case Some(dsConfig) => parserConfigDetails(dsConfig.parserConfig)
    )

  private def parserConfigDetails(cfg: ParserConfig): HtmlElement = cfg match
    case CsvParserConfig(hasHeader, columnDefinitions, amountField) =>
      div(
        div(
          cls := "text-sm mb-2 text-md-on-surface dark:text-gray-200",
          s"Format: CSV${if hasHeader then " (first row is a header, skipped)" else " (no header row)"}"
        ),
        Table.create(
          Seq("Column #", "Field Name"),
          columnDefinitions.toSeq
            .sortBy(_._1)
            .map((idx, name) =>
              Seq(span(idx.toString), span(if amountField.contains(idx) then s"$name (amount)" else name))
            )
        )
      )
    case BaiParserConfig(fieldMappings) =>
      div(
        div(cls := "text-sm mb-2 text-md-on-surface dark:text-gray-200", "Format: BAI2"),
        Table.create(
          Seq("BAI Field", "Stored As"),
          fieldMappings.toSeq.map((field, name) => Seq(span(field.toString), span(name)))
        )
      )
    case JsonParserConfig(fieldMappings, amountPath) =>
      val amountOnlyRow =
        amountPath.filterNot(fieldMappings.contains).map(p => Seq(span(p), span("(amount)"))).toSeq
      div(
        div(cls := "text-sm mb-2 text-md-on-surface dark:text-gray-200", "Format: JSON (one JSON document per line)"),
        Table.create(
          Seq("JSON Path", "Field Name"),
          fieldMappings.toSeq.map((path, name) =>
            Seq(span(path), span(if amountPath.contains(path) then s"$name (amount)" else name))
          ) ++ amountOnlyRow
        )
      )
    case FixedWidthParserConfig(hasHeader, fields) =>
      div(
        div(
          cls := "text-sm mb-2 text-md-on-surface dark:text-gray-200",
          s"Format: Fixed Width${if hasHeader then " (first row is a header, skipped)" else " (no header row)"}"
        ),
        Table.create(
          Seq("Start", "End", "Field Name"),
          fields.map(f =>
            Seq(
              span(f.startPosition.toString),
              span(f.endPosition.toString),
              span(if f.isAmount then s"${f.name} (amount)" else f.name)
            )
          )
        )
      )

  private def uploadSection(state: Var[State], fileReadBus: EventBus[String]): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-4 max-w-lg",
      h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "Upload a sample file"),
      div(
        cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mb-3",
        s"Parses the file against this feed's configured parser without saving anything — " +
          s"no file inventory entry, no movements written. Only the first $maxLines lines are parsed."
      ),
      input(
        idAttr := "test-parse-file-input",
        typ    := "file",
        cls    := "block w-full text-sm text-md-on-surface-variant file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-medium file:bg-md-primary file:text-md-on-primary hover:file:bg-md-primary-hover",
        onChange --> { _ =>
          state.update(_.copy(status = UploadStatus.Reading, result = None))
          val fileInput = dom.document.getElementById("test-parse-file-input").asInstanceOf[HTMLInputElement]
          val file      = fileInput.files(0)
          val reader    = new FileReader()
          reader.onload  = _ => fileReadBus.emit(reader.result.asInstanceOf[String])
          reader.onerror = _ => state.update(_.copy(status = UploadStatus.Failed("Failed to read file")))
          reader.readAsText(file)
        }
      ),
      child <-- state.signal.map(s => statusBadge(s.status))
    )

  private def statusBadge(status: UploadStatus): HtmlElement = status match
    case UploadStatus.Idle      => div()
    case UploadStatus.Reading   => div(cls := "text-xs text-blue-500 mt-2", "Reading file...")
    case UploadStatus.Uploading => div(cls := "text-xs text-blue-500 mt-2", "Parsing...")
    case UploadStatus.Done      => div(cls := "text-xs text-green-600 font-medium mt-2", "Done")
    case UploadStatus.Failed(e) => div(cls := "text-xs text-red-500 mt-2", s"Error: $e")

  private def resultSection(s: State): HtmlElement =
    s.result match
      case None => div()
      case Some(r) =>
        div(
          if r.truncated then
            div(
              cls := "text-sm text-md-warning dark:text-md-warning-dark mb-3",
              s"File has more than ${r.linesProcessed} lines — only the first ${r.linesProcessed} were parsed."
            )
          else div(),
          div(
            cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-4",
            s"${r.rows.size} row(s) parsed, ${r.errors.size} error(s), ${r.linesProcessed} line(s) processed"
          ),
          if r.errors.isEmpty then div()
          else
            div(
              cls := "border border-md-error rounded-lg p-4 mb-4",
              h3(cls := "font-medium mb-3 text-md-error", "Errors"),
              Table.create(
                Seq("Line", "Message"),
                r.errors.map(e => Seq(span(e.lineNumber.toString), span(e.message)))
              )
            )
          ,
          if r.rows.isEmpty then div()
          else
            div(
              cls := s"${Card.cls} p-4",
              h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Parsed Rows"),
              parsedRowsTable(r.rows)
            )
        )

  // props is a Map whose keys depend entirely on the feed's parser config (different fields per
  // CSV/BAI/JSON config, even per feed) — the column set can't be known ahead of time, so it's
  // derived from the union of every row's actual keys instead of hardcoded.
  private def parsedRowsTable(rows: Seq[ParsedRowPayload]): HtmlElement =
    val propKeys = rows.flatMap(_.props.keys).distinct.sorted
    val headers  = Seq("Line") ++ propKeys ++ Seq("Amount")
    Table.create(
      headers,
      rows.map { r =>
        val propCells = propKeys.map(k => span(r.props.getOrElse(k, "")))
        Seq(span(r.lineNumber.toString)) ++ propCells ++ Seq(span(r.amount.map(_.toString).getOrElse("")))
      }
    )
