package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.FundFlowPayload
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{Buttons, Form}
import upickle.default.*

object MaintainFundFlowPage:

  def apply(): HtmlElement =
    val initial = AppState.activeFundFlow.now().getOrElse(FundFlowPayload())
    val ff      = Var(initial)
    val saveBus = EventBus[Unit]()

    div(
      saveBus.events.flatMapSwitch { _ =>
        val f     = ff.now()
        val token = SessionStore.token
        if f.id.isDefined
        then
          ApiClient.put[FundFlowPayload, FundFlowPayload](HttpPath.url(s"${HttpPath.fundFlow}/${f.id.get}"), f, token)
        else ApiClient.post[FundFlowPayload, FundFlowPayload](HttpPath.url(HttpPath.fundFlow), f, token)
      }.apiBind(_ => Nav.toFundFlows()),
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Fund Flow"),
        Form.field("name", "Name", initial.name, v => ff.update(_.copy(name = v))),
        Form.field(
          "dailyCutoffHours",
          "Daily Report Cutoff (hour of day, 0-23)",
          initial.dailyCutoffHours.toString,
          v => ff.update(_.copy(dailyCutoffHours = v.trim.toIntOption.getOrElse(0)))
        ),
        initial.createdAt.fold(div())(ca =>
          Form.field("created_at", "Created At", ViewHelper.formatDateTime(ca), enabled = false)
        ),
        div(
          cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.toFundFlows())),
          Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
        )
      )
    )
