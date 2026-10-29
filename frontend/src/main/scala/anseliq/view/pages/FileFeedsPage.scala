package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  BaiParserConfig,
  CsvParserConfig,
  FileFeedPayload,
  FixedWidthParserConfig,
  JsonParserConfig,
  ParserConfig,
  S3ConnectionConfig
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{Buttons, PageHeader, Table}

object FileFeedsPage:

  case class State(feeds: Seq[FileFeedPayload] = Seq.empty)

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[Unit]()

    PageHeader.page(
      EventStream
        .merge(EventStream.fromValue((), emitOnce = true), fetchBus.events)
        .flatMapSwitch { _ =>
          ApiClient.get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        }
        .apiBind(feeds => state.update(_.copy(feeds = feeds))),
      PageHeader.titleRow(
        cls := "flex items-center justify-between",
        PageHeader.title("File Feeds"),
        PageHeader.refreshButton(Observer[Unit](_ => fetchBus.emit(())))
      ),
      div(
        cls := "w-full px-8",
        Option.when(SessionStore.canEdit)(Buttons.linkButton("+ New Feed", Observer[Unit](_ => Nav.toMaintainFileFeed()))),
        div(
          cls := "mt-4",
          child <-- state.signal.map { s =>
            Table.create(
              Seq("Name", "Format", "S3 Bucket", "Path", "Cadence (hours)", ""),
              s.feeds.map { feed =>
                val ds = feed.dataSourceConfig
                val (bucket, path) = ds.map(_.connectionConfig) match
                  case Some(S3ConnectionConfig(b, p)) => (b, p)
                  case None                           => ("", "")
                Seq(
                  span(if feed.name.nonEmpty then feed.name else "(unnamed feed)"),
                  span(ds.map(c => formatLabel(c.parserConfig)).getOrElse("—")),
                  span(if bucket.nonEmpty then bucket else "—"),
                  span(if path.nonEmpty then path else "—"),
                  span(feed.expectedCadenceHours.map(_.toString).getOrElse("—")),
                  div(
                    cls := "flex gap-2",
                    Option.when(SessionStore.canEdit)(
                      Buttons.textButton("Edit", Observer[Unit](_ => Nav.toMaintainFileFeed(feed.id.getOrElse("new"))))
                    ),
                    Buttons.textButton("Test Parser", Observer[Unit](_ => Nav.toTestParser(feed.id.getOrElse(""))))
                  )
                )
              }
            )
          }
        )
      )
    )

  private def formatLabel(cfg: ParserConfig): String = cfg match
    case _: CsvParserConfig        => "CSV"
    case _: BaiParserConfig        => "BAI2"
    case _: JsonParserConfig       => "JSON"
    case _: FixedWidthParserConfig => "Fixed Width"
