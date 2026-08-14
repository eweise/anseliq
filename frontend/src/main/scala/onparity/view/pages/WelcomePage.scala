package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.BackendApiUrl
import onparity.common.model.RandomMessage
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.components.Icons
import upickle.default.*

object WelcomePage:

  def apply(): HtmlElement =
    val message  = Var(Option.empty[String])
    val fetchBus = EventBus[Unit]()

    div(
      fetchBus.events.flatMapSwitch { _ =>
        ApiClient.get[RandomMessage](BackendApiUrl.randomMessage2, SessionStore.token)
      } --> {
        case Right(RandomMessage(text)) => message.set(Some(text))
        case Left(err)                  => AppState.globalError.set(Some(err))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8",
        h1(cls := "mb-2 text-4xl font-bold tracking-tight text-gray-900 dark:text-white", "OnParity")
      ),
      div(
        cls := "max-w transition-transform duration-300 ease-in-out hover:-translate-y-2 bg-white border border-gray-200 rounded-lg shadow-xl dark:bg-gray-600",
        div(
          cls := "flex items-center w-[60rem] h-[12rem] p-5 space-y-6 text-center",
          div(
            cls := "mb-3 font-large text-gray-900 dark:text-gray-300",
            div(
              cls := "flex flex-col space-y-4 w-[50rem]",
              div(cls := "text-green-400 text-2xl font-semibold", "Message from server"),
              div(
                cls := "font-mono text-sm italic text-gray-600 dark:text-white break-words whitespace-normal",
                child <-- message.signal.map(_.getOrElse("Empty...is Server running?"))
              )
            )
          ),
          button(
            cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full mx-6 ml-auto hover:bg-green-500",
            typ := "button",
            onClick --> (_ => fetchBus.emit(())),
            Icons.refresh
          )
        )
      )
    )
