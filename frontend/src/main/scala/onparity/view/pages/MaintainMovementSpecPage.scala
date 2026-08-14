package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AccountPayload, MovementSpecPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, DataSourceConfigWidget, Form}
import upickle.default.*

object MaintainMovementSpecPage:

  def apply(): HtmlElement =
    AppState.activeFundFlow.now() match
      case None     => div(onMountCallback(_ => Nav.toFundFlows()))
      case Some(ff) => renderPage(ff)

  private def renderPage(ff: onparity.common.model.FundFlowPayload): HtmlElement =
    val ffId     = ff.id.getOrElse("")
    val initial  = AppState.editMovementSpec.now().getOrElse(MovementSpecPayload())
    val spec     = Var(initial)
    val accounts = Var(Seq.empty[AccountPayload])
    val saveBus  = EventBus[Unit]()

    val (dsWidget, dsSignal) = DataSourceConfigWidget(initial.dataSourceConfig)

    div(
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token) --> {
        case Right(accs) => accounts.set(accs)
        case Left(e)     => AppState.globalError.set(Some(e))
      },
      saveBus.events.sample(dsSignal).flatMapSwitch { dsConfig =>
        val s     = spec.now().copy(dataSourceConfig = dsConfig)
        val token = SessionStore.token
        if s.id.isDefined
        then
          ApiClient.put[MovementSpecPayload, MovementSpecPayload](
            HttpPath.url(s"${HttpPath.movementSpec}/${s.id.get}"),
            s,
            token
          )
        else ApiClient.post[MovementSpecPayload, MovementSpecPayload](HttpPath.url(HttpPath.movementSpec), s, token)
      } --> {
        case Right(_) => Nav.back()
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Movement Spec"),
        Form.field("name", "Name", initial.name, v => spec.update(_.copy(name = v))),
        child <-- accounts.signal.map { accs =>
          val selectableAccs =
            if initial.id.isEmpty && initial.inAccountId.nonEmpty && initial.outAccountId.nonEmpty then
              accs.filter(a => a.id.contains(initial.inAccountId) || a.id.contains(initial.outAccountId))
            else accs
          div(
            accountSelect(
              "inAccountId",
              "In Account",
              initial.inAccountId,
              selectableAccs,
              v => spec.update(_.copy(inAccountId = v))
            ),
            accountSelect(
              "outAccountId",
              "Out Account",
              initial.outAccountId,
              selectableAccs,
              v => spec.update(_.copy(outAccountId = v))
            )
          )
        },
        dsWidget,
        div(
          cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.back())),
          Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
        )
      )
    )

  private def accountSelect(
    id:       String,
    lbl:      String,
    selected: String,
    accounts: Seq[AccountPayload],
    changeFn: String => Unit
  ): HtmlElement =
    div(
      cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
        lbl
      ),
      select(
        idAttr := id,
        cls    := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-base focus:outline-none rounded",
        onMountCallback(ctx => ctx.thisNode.ref.value = selected),
        onChange.mapToValue --> Observer[String](changeFn)
      ).amend(accounts.map(a => option(value := a.id.getOrElse(""), a.name))*)
    )
