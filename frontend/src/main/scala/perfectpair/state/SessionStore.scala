package perfectpair.state

import com.raquo.laminar.api.L.*
import org.scalajs.dom

case class Session(accessToken: String, refreshToken: String)

object SessionStore:
  val current: Var[Option[Session]] = Var(None)

  def restore(): Unit =
    val at = dom.window.localStorage.getItem("accessToken")
    val rt = dom.window.localStorage.getItem("refreshToken")
    if at != null && rt != null then
      current.set(Some(Session(at, rt)))

  def save(at: String, rt: String): Unit =
    dom.window.localStorage.setItem("accessToken", at)
    dom.window.localStorage.setItem("refreshToken", rt)
    current.set(Some(Session(at, rt)))

  def clear(): Unit =
    dom.window.localStorage.removeItem("accessToken")
    dom.window.localStorage.removeItem("refreshToken")
    current.set(None)

  def token: String = current.now().map(_.accessToken).getOrElse("")
