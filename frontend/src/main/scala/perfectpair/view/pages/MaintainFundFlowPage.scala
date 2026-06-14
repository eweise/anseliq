package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.FundFlowPayload
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Form}
import upickle.default.*

object MaintainFundFlowPage:

  def apply(): HtmlElement =
    val initial  = AppState.activeFundFlow.now().getOrElse(FundFlowPayload())
    val ff       = Var(initial)
    val saveBus  = EventBus[Unit]()

    div(
      saveBus.events.flatMapSwitch { _ =>
        val f     = ff.now()
        val token = SessionStore.token
        if f.id.isDefined
        then ApiClient.put[FundFlowPayload, FundFlowPayload](HttpPath.url(s"${HttpPath.fundFlow}/${f.id.get}"), f, token)
        else ApiClient.post[FundFlowPayload, FundFlowPayload](HttpPath.url(HttpPath.fundFlow), f, token)
      } --> {
        case Right(_) => Nav.toFundFlows()
        case Left(e)  => AppState.globalError.set(Some(e))
      },

      cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4",
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface mb-7", "Fund Flow"),
        Form.field("name", "Name", initial.name, v => ff.update(_.copy(name = v))),
        initial.createdAt.fold(div())(ca =>
          Form.field("created_at", "Created At", ViewHelper.formatDateTime(ca), enabled = false)
        ),
        div(cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.toFundFlows())),
          Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
        )
      )
    )
