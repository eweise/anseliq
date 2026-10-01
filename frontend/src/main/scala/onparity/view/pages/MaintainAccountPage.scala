package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AccountPayload, AccountTypePayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Form}

object MaintainAccountPage:

  private val accountTypeOptions = AccountTypePayload.values.toSeq.map(t => t.toString -> t.toString)

  // Rendered inside Modal by FundFlowPage (its only caller) — onClose defaults to Nav.back() so
  // this still works if ever reached directly via its route (/components/accounts/:id/maintain).
  def apply(accountId: String, onClose: () => Unit = () => Nav.back()): HtmlElement =
    val fundFlow = AppState.activeFundFlow.now()
    val ffId     = fundFlow.flatMap(_.id).getOrElse("")
    val initial = AccountPayload(
      id          = if accountId == "new" then None else Some(accountId),
      fundFlowId  = ffId,
      accountType = AccountTypePayload.Primary
    )
    val account        = Var(initial)
    val setNameBus     = EventBus[String]()
    val setAccountType = EventBus[String]()
    val saveBus        = EventBus[Unit]()

    div(
      // Fires the initial load directly within the same pipeline that consumes it, rather than via
      // a separate onMountCallback + EventBus.emit: EventBus has no replay buffer, so an emit that
      // happens before the flatMapSwitch below has actually subscribed is silently dropped. That
      // race wasn't observable while this rendered as a plain routed page (mount ordering happened
      // to work out), but became real once it started being constructed inside FundFlowPage's
      // reactive Modal block. Matches the same pattern searchMovementsTab uses.
      (if accountId != "new" then EventStream.fromValue(()) else EventStream.empty).flatMapSwitch { _ =>
        ApiClient.get[AccountPayload](HttpPath.url(s"${HttpPath.account}/$accountId"), SessionStore.token)
      }.apiBind { a =>
        account.set(a)
        setNameBus.emit(a.name)
        setAccountType.emit(a.accountType.toString)
      },
      saveBus.events.flatMapSwitch { _ =>
        val a     = account.now()
        val token = SessionStore.token
        if a.id.isDefined
        then ApiClient.put[AccountPayload, AccountPayload](HttpPath.url(s"${HttpPath.account}/${a.id.get}"), a, token)
        else ApiClient.post[AccountPayload, AccountPayload](HttpPath.url(HttpPath.account), a, token)
      }.apiBind(_ => onClose()),
      h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Account"),
      Form.field("name", "Name", initial.name, v => account.update(_.copy(name = v)), updates = setNameBus.events),
      Form.selectField(
        "accountType",
        "Account Type",
        accountTypeOptions,
        initial.accountType.toString,
        v => account.update(_.copy(accountType = AccountTypePayload.valueOf(v))),
        updates = setAccountType.events
      ),
      div(
        cls := "flex justify-end gap-2 mt-6",
        Buttons.textButton("Cancel", Observer[Unit](_ => onClose())),
        Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
      )
    )
