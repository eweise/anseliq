package com.eweise.perfectpair.util

import tyrian.Cmd
import tyrian.cmds.*
import zio.*
import com.eweise.perfectpair.until.HttpHelper
import com.eweise.perfectpair.model.Msg
object Authentication {

  def authenticate(username: String, password: String): Cmd[Task, Msg] =
    HttpHelper.login(username, password)
}
