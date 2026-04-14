package perfectpair.view.pages

import perfectpair.model.Msg as AppMsg
import perfectpair.util.{ElementIdGenerator, HttpHelper, LocalStorageHelper}
import perfectpair.view.components.Icons
import tyrian.*
import tyrian.Html.{attribute as attr, *}
import zio.Task
import zio.interop.catz.*

object LoginPage {

  def update(model: Model, msg: Msg): (Model, tyrian.Cmd[Task, AppMsg]) =
    msg match
      case Msg.UpdateUsername(v) =>
        (model.copy(username = v), tyrian.Cmd.None)
      case Msg.UpdatePassword(v) =>
        (model.copy(password = v), tyrian.Cmd.None)
      case Msg.Submit =>
        (model.copy(isLoading = true), HttpHelper.login(model.username, model.password))
      case Msg.LoginSuccess(at, rt) =>
        (Model.init, LocalStorageHelper.storeSession(at, rt) |+| tyrian.Cmd.Emit(AppMsg.RestoredSession(at, rt)))
      case Msg.LoginFailure(message) =>
        (model.copy(error = Some(message), isLoading = false), tyrian.Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    div(cls := "h-screen flex justify-center bg-base-150")(
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white border border-gray-200 rounded-lg shadow-sm sm:p-6 md:p-8 dark:bg-gray-800 dark:border-gray-700"
      )(
        form(cls := "space-y-6", attr("action", "#"))(
          h5(cls := "text-xl font-medium text-gray-900 dark:text-white")("Just click Login"),
          formField("Username", model.username, v => AppMsg.LoginMsg(Msg.UpdateUsername(v)), "text"),
          formField("Password", model.password, v => AppMsg.LoginMsg(Msg.UpdatePassword(v)), "password"),
          p(cls := "text-red-500")(model.error.getOrElse("")),
          div(cls := "flex items-start")(
            div(cls := "flex items-center h-5")(
              input(
                id     := "remember",
                `type` := "checkbox",
                cls    := "w-4 h-4 checked:bg-purple-500 dark:checked:bg-purple-400 border border-gray-300 rounded-sm bg-gray-50 focus:ring-3 focus:ring-purple-300 dark:bg-gray-700 dark:border-gray-600 dark:focus:ring-purple-600 dark:ring-offset-gray-800 dark:focus:ring-offset-gray-800"
              )
            ),
            label(`for` := "remember", cls := "ms-2 text-sm font-medium text-gray-900 dark:text-gray-300")(
              "Remember me"
            ),
            a(
              cls := "ms-auto text-sm text-purple-700 hover:underline dark:text-purple-400"
            )("Forgot Password?")
          ),
          button(
            onClick(AppMsg.LoginMsg(Msg.Submit)),
            disabled(model.isSubmitDisabled),
            cls := s"w-full text-white bg-purple-700 hover:bg-purple-800 focus:ring-4 focus:outline-none focus:ring-purple-300 font-medium rounded-lg text-sm px-5 py-2.5 text-center dark:bg-purple-600 dark:hover:bg-purple-700 dark:focus:ring-purple-800 ${if model.isSubmitDisabled then "btn-disabled" else ""}"
          )("Login"),
          span(
            style(if model.isLoading then CSS.display("block") else CSS.display("none")),
            cls := "loading loading-ring loading-lg text-info"
          )()
        )
      )
    )

  private def formField(
    name:     String,
    _value:   String,
    _onInput: String => AppMsg,
    _type:    "password" | "text"
  ): Html[AppMsg] =
    val icon         = if _type == "password" then Icons.lock else Icons.user
    val _placeholder = if _type == "password" then "••••••••" else ""
    val _id          = ElementIdGenerator.generate("login-")
    div()(
      label(`for` := _id, cls := "block mb-2 text-sm font-medium text-gray-900 dark:text-white")(name),
      div(cls := "relative")(
        div(cls := "absolute inset-y-0 start-0 flex items-center ps-3.5 pointer-events-none")(icon),
        input(
          onInput(_onInput),
          id          := _id,
          `type`      := _type,
          value       := _value,
          placeholder := _placeholder,
          cls         := "bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg focus:ring-purple-500 focus:border-purple-500 block w-full ps-10 p-2.5  dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-purple-500 dark:focus:border-purple-500"
        )
      )
    )

  enum Msg:
    case UpdateUsername(value: String)
    case UpdatePassword(value: String)
    case Submit
    case LoginSuccess(accessToken: String, refreshToken: String)
    case LoginFailure(message: String)

  case class Model(
    username:  String,
    password:  String,
    error:     Option[String],
    isLoading: Boolean
  ):
    def isSubmitDisabled: Boolean = username.isBlank() || password.isBlank()

  object Model:
    val init: Model = Model("Scala", "nopassword", None, false)
}
