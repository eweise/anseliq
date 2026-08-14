package onparity.view.pages

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import onparity.common.http.{BackendApiUrl, HttpPath}
import onparity.common.model.{FundFlowPayload, LoginSuccess}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Icons}
import upickle.default.*
import zio.json.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object LoginPage:

  case class State(username: String = "Scala", password: String = "nopassword", error: Option[String] = None)

  private def doLogin(username: String, password: String): EventStream[Either[String, (String, String)]] =
    EventStream.fromFuture(
      dom
        .fetch(
          BackendApiUrl.login,
          js.Dynamic
            .literal(
              method  = "POST",
              headers = js.Dynamic.literal("Content-Type" -> "application/json"),
              body    = s"""{"username":"$username","password":"$password"}"""
            )
            .asInstanceOf[dom.RequestInit]
        )
        .toFuture
        .flatMap { resp =>
          resp.text().toFuture.map { body =>
            if resp.ok then
              body.fromJson[LoginSuccess] match
                case Right(LoginSuccess(at, rt)) => Right((at, rt))
                case _                           => Left("Failed to parse login response")
            else Left(s"Login failed")
          }
        }
        .recover { case e => Left(e.getMessage) }
    )

  def apply(): HtmlElement =
    val state    = Var(State())
    val loginBus = EventBus[Unit]()

    div(
      loginBus.events.flatMapSwitch { _ =>
        val s = state.now()
        doLogin(s.username, s.password)
      } --> {
        case Right((at, rt)) =>
          SessionStore.save(at, rt)
          Nav.push("/")
        case Left(err) =>
          state.update(_.copy(error = Some(err)))
      },
      cls := "h-screen flex justify-center bg-md-surface-variant dark:bg-gray-900",
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm sm:p-6 md:p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-4", "Login"),
        formField("Username", "text", Icons.user, v => state.update(_.copy(username = v))),
        formField("Password", "password", Icons.lock, v => state.update(_.copy(password = v))),
        child <-- state.signal.map(_.error.fold(div())(msg => p(cls := "text-red-500 mb-2", msg))),
        div(cls := "mt-4 text-center", Buttons.linkButton("Login", Observer[Unit](_ => loginBus.emit(()))))
      )
    )

  private def formField(lbl: String, inputType: String, icon: HtmlElement, inputFn: String => Unit): HtmlElement =
    div(
      cls := "mb-4",
      label(cls := "block mb-2 text-sm font-medium text-gray-900", lbl),
      div(
        cls := "relative",
        div(cls := "absolute inset-y-0 start-0 flex items-center ps-3.5 pointer-events-none", icon),
        input(
          typ := inputType,
          cls := "bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg block w-full ps-10 p-2.5",
          onInput.mapToValue --> Observer[String](inputFn)
        )
      )
    )
