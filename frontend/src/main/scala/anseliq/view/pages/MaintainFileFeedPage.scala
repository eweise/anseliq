package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.FileFeedPayload
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.components.{Buttons, DataSourceConfigWidget, Form}

// Owns "where a file comes from and how to parse it" — the half of what used to live on
// MovementSpec before multi-account feeds (e.g. BAI2) needed to be split from "which account pair
// a row posts against". MaintainMovementSpecPage picks one of these feeds rather than configuring
// its own connection/parser.
object MaintainFileFeedPage:

  private enum LoadState:
    case Loading
    case Ready(feed: FileFeedPayload)

  def apply(id: String): HtmlElement =
    AppState.activeLedger.now() match
      case None    => div(onMountCallback(_ => Nav.toLedgers()))
      case Some(_) => renderPage(id)

  private def renderPage(id: String): HtmlElement =
    val blank     = FileFeedPayload()
    val loadState = Var[LoadState](if id == "new" then LoadState.Ready(blank) else LoadState.Loading)
    val loadBus   = EventBus[Unit]()

    div(
      loadBus.events
        .flatMapSwitch(_ => ApiClient.get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token))
        .apiBind(feeds => loadState.set(LoadState.Ready(feeds.find(_.id.contains(id)).getOrElse(blank)))),
      onMountCallback(_ => if id != "new" then loadBus.emit(())),
      child <-- loadState.signal.map {
        case LoadState.Loading =>
          div(
            cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-center justify-center pt-20 px-4",
            span(cls := "text-md-on-surface-variant", "Loading...")
          )
        case LoadState.Ready(initial) => renderForm(initial)
      }
    )

  private def renderForm(initial: FileFeedPayload): HtmlElement =
    val feed    = Var(initial)
    val saveBus = EventBus[Unit]()

    val (dsWidget, dsSignal, dsErrors) = DataSourceConfigWidget(initial.dataSourceConfig)

    div(
      saveBus.events
        .sample(dsSignal)
        .flatMapSwitch { dsConfig =>
          val f     = feed.now().copy(dataSourceConfig = dsConfig)
          val token = SessionStore.token
          if f.id.isDefined
          then
            ApiClient.put[FileFeedPayload, FileFeedPayload](HttpPath.url(s"${HttpPath.fileFeed}/${f.id.get}"), f, token)
          else ApiClient.post[FileFeedPayload, FileFeedPayload](HttpPath.url(HttpPath.fileFeed), f, token)
        }
        .apiBind(_ => Nav.back()),
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "File Feed"),
        Form.field("name", "Name", initial.name, v => feed.update(_.copy(name = v))),
        dsWidget,
        div(
          cls := "mt-4",
          Form.field(
            "expectedCadenceHours",
            "Expected File Cadence (hours)",
            initial.expectedCadenceHours.map(_.toString).getOrElse(""),
            v => feed.update(_.copy(expectedCadenceHours = v.trim.toIntOption))
          )
        ),
        div(
          cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.back())),
          Buttons
            .linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
            .amend(
              cls := "disabled:opacity-40 disabled:cursor-not-allowed",
              disabled <-- dsErrors.map(_.nonEmpty)
            )
        )
      )
    )
