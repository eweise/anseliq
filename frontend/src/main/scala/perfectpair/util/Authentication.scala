package perfectpair.util

import tyrian.Cmd
import tyrian.cmds.*
import zio.*
import perfectpair.model.Msg

object Authentication {

  def authenticate(username: String, password: String): Cmd[Task, Msg] =
    HttpHelper.login(username, password)
}
