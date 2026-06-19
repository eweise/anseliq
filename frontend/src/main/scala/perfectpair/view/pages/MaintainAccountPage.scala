package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.AccountPayload
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Form}
import upickle.default.*

object MaintainAccountPage:

  def apply(accountId: String): HtmlElement =
    val fundFlow   = AppState.activeFundFlow.now()
    val ffId       = fundFlow.flatMap(_.id).getOrElse("")
    val initial    = AccountPayload(id = if accountId == "new" then None else Some(accountId), fundFlowId = ffId)
    val account    = Var(initial)
    val setNameBus = EventBus[String]()
    val loadBus    = EventBus[Unit]()
    val saveBus    = EventBus[Unit]()

    div(
      onMountCallback { _ =>
        if accountId != "new" then loadBus.emit(())
      },
      loadBus.events.flatMapSwitch { _ =>
        ApiClient.get[AccountPayload](HttpPath.url(s"${HttpPath.account}/$accountId"), SessionStore.token)
      } --> {
        case Right(a) =>
          account.set(a)
          setNameBus.emit(a.name)
        case Left(e) => AppState.globalError.set(Some(e))
      },
      saveBus.events.flatMapSwitch { _ =>
        val a     = account.now()
        val token = SessionStore.token
        if a.id.isDefined
        then ApiClient.put[AccountPayload, AccountPayload](HttpPath.url(s"${HttpPath.account}/${a.id.get}"), a, token)
        else ApiClient.post[AccountPayload, AccountPayload](HttpPath.url(HttpPath.account), a, token)
      } --> {
        case Right(_) => Nav.back()
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface mb-7", "Account"),
        Form.field("name", "Name", initial.name, v => account.update(_.copy(name = v)), updates = setNameBus.events),
        div(
          cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.back())),
          Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
        )
      )
    )
