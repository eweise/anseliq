package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{LoginRequest, LoginSuccess}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.SessionStore
import onparity.view.components.{Buttons, Icons}

object LoginPage:

  case class State(email: String = "", password: String = "", error: Option[String] = None)

  def apply(): HtmlElement =
    val state    = Var(State())
    val loginBus = EventBus[Unit]()

    div(
      loginBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.post[LoginSuccess, LoginRequest](
          HttpPath.url(HttpPath.login),
          LoginRequest(s.email, s.password),
          token = ""
        )
      } --> {
        case Right(LoginSuccess(at, rt)) =>
          SessionStore.save(at, rt)
          Nav.push("/")
        case Left(err) =>
          state.update(_.copy(error = Some(err)))
      },
      cls := "h-screen flex justify-center bg-md-surface-variant dark:bg-gray-900",
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm sm:p-6 md:p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-4", "Login"),
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
