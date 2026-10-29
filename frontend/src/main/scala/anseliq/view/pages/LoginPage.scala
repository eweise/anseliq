package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{LoginRequest, LoginSuccess}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.SessionStore
import anseliq.view.components.{Brand, Buttons, Icons}

object LoginPage:

  case class State(email: String = "", password: String = "", error: Option[String] = None)

  // Backend errors surface as an uncaught exception's raw message + stack trace in the response
  // body (an app-wide characteristic of this backend's error handling, not something to work
  // around here — see MaintainUserPage's identical helper) — take just the first line so a bad
  // login shows one readable sentence instead of a multi-page trace that reads like a network
  // failure.
  private def firstLine(message: String): String = message.linesIterator.nextOption().getOrElse(message)

  def apply(): HtmlElement =
    val state    = Var(State())
    val loginBus = EventBus[Unit]()

    div(
      loginBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.post[LoginSuccess, LoginRequest](
          HttpPath.login,
          LoginRequest(s.email, s.password),
          token = ""
        )
      } --> {
        case Right(LoginSuccess(at, rt)) =>
          SessionStore.save(at, rt)
          Nav.push("/")
        case Left(err) =>
          state.update(_.copy(error = Some(firstLine(err))))
      },
      cls := "h-screen flex justify-center bg-md-surface-variant dark:bg-gray-900",
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm sm:p-6 md:p-8",
        div(cls := "mb-6 flex justify-center", Brand.logo(markPx = 32, textCls = "text-2xl")),
        h2(cls  := "text-xl font-medium text-md-on-surface dark:text-white mb-4", "Sign in"),
        formField("Email", "email", Icons.user, v => state.update(_.copy(email = v))),
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
