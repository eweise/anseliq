package com.eweise.perfectpair.page

import tyrian.Cmd
import tyrian.Html
import tyrian.Html.div
import java.util.UUID
import tyrian.cmds.Logger
import zio.*
import zio.interop.catz.*
import com.eweise.perfectpair.util.PrettyLogger
import com.eweise.perfectpair.view.pages.LoginPage
import com.eweise.perfectpair.until.HttpHelper
import com.eweise.perfectpair.model.*
import com.eweise.perfectpair.view.pages.*

enum Page(
  val path: String,
  val render: Model => Html[Msg],
  beforeEnter: Model => Cmd[Task, Msg] = _ => Cmd.None, // e.g: side effect for loading data
  val isSecured: Boolean = false
) {
  def doNavigate(model: Model): Cmd[Task, Msg] = beforeEnter(model) |+| Cmd.emit(Msg.DoNavigate(this))

  case Login   extends Page("/login", model => LoginPage(model.loginForm), isSecured = false)
  case Home    extends Page("/", model => Welcome(model.homeState), _ => HttpHelper.fetchServerMessage)
  case Accounts extends Page("/components/accounts", model => AccountsView(), _ => PrettyLogger.info("Entering Accounts page"))
  case Toggle  extends Page("/components/toggle", model => ToggleView(), _ => PrettyLogger.info("Entering Toggle page"))
}
