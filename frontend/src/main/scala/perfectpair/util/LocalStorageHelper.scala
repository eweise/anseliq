package perfectpair.util

import cats.implicits
import org.scalajs.dom
import perfectpair.model.Msg
import perfectpair.page.Page
import tyrian.Cmd
import tyrian.cmds.LocalStorage
import zio.interop.catz.*
import zio.{Task, ZIO}

object LocalStorageHelper:

  val handler: PartialFunction[LocalStorage.Result, Msg] = {
    case LocalStorage.Result.Success => Msg.NoOp
    case e                           => Msg.Error(e.toString)
  }
  val cleanStorageWhenLogout: Cmd[Task, Msg] =
    LocalStorage.removeItem("accessToken")(handler) |+| LocalStorage.removeItem("refreshToken")(handler)

  def storeSession(accessToken: String, refreshToken: String): Cmd[Task, Msg] =
    Cmd.Batch(
      LocalStorage.setItem("accessToken", accessToken)(handler),
      LocalStorage.setItem("refreshToken", refreshToken)(handler)
    )

  /**
   * In case users reload SPA, session will be obtained from local storage
   */
  def obtainSessionCmd: Cmd[Task, Msg] =
    // TODO: might need to check token expiry
    val tryLoginFromLocalStorage = ZIO.attempt {
      val accessToken  = dom.window.localStorage.getItem("accessToken")
      val refreshToken = dom.window.localStorage.getItem("refreshToken")

      if (accessToken != null && refreshToken != null)
        Msg.RestoredSession(accessToken, refreshToken)
      else
        Msg.NoOp
//        Msg.NavigateTo(Page.Login)
    }

    Cmd.Run(tryLoginFromLocalStorage)
