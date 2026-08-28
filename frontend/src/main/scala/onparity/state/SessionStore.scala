package onparity.state

import com.raquo.laminar.api.L.*
import onparity.common.util.JwtHelper
import onparity.common.util.JwtHelper.UserClaim
import org.scalajs.dom

case class Session(accessToken: String, refreshToken: String)

object SessionStore:
  val current: Var[Option[Session]] = Var(None)

  // Decoded from the token already held in memory — no network round-trip needed. Unverified
  // (no signature check) is fine here: this is only used to decide what the UI shows (nav
  // visibility, route guarding), never to authorize an actual action — the backend independently
  // verifies the token's signature on every request regardless of what the frontend displays.
  val currentUser: Signal[Option[UserClaim]] =
    current.signal.map(_.flatMap(s => JwtHelper.getUnverifiedUserClaim(s.accessToken).toOption))

  // Signal.now() isn't public (only Var.now() is) — this is the synchronous-snapshot equivalent
  // for callers (e.g. LeftNav) that need a value at mount time rather than a reactive binding.
  def currentUserNow: Option[UserClaim] =
    current.now().flatMap(s => JwtHelper.getUnverifiedUserClaim(s.accessToken).toOption)

  def restore(): Unit =
    val at = dom.window.localStorage.getItem("accessToken")
    val rt = dom.window.localStorage.getItem("refreshToken")
    if at != null && rt != null then current.set(Some(Session(at, rt)))

  def save(at: String, rt: String): Unit =
    dom.window.localStorage.setItem("accessToken", at)
    dom.window.localStorage.setItem("refreshToken", rt)
    current.set(Some(Session(at, rt)))

  def clear(): Unit =
    dom.window.localStorage.removeItem("accessToken")
    dom.window.localStorage.removeItem("refreshToken")
    current.set(None)

  def token: String = current.now().map(_.accessToken).getOrElse("")
