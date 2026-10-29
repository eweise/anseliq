package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.ExceptionPayload
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{ExceptionViews, PageHeader}
import upickle.default.*

object ExceptionsPage:

  case class State(exceptions: Seq[ExceptionPayload] = Seq.empty)

  def apply(): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[Seq[ExceptionPayload]](HttpPath.exceptions, SessionStore.token)

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(ds => state.update(_.copy(exceptions = ds))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(PageHeader.title("Exceptions")),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map { s =>
          ExceptionViews.table(s.exceptions)
        }
      )
    )
