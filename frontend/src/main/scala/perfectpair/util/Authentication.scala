package perfectpair.util

import perfectpair.model.Msg
import tyrian.Cmd
import tyrian.cmds.*
import zio.*

object Authentication {

  def authenticate(username: String, password: String): Cmd[Task, Msg] =
    HttpHelper.login(username, password)
}
