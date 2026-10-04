package anseliq.main

import com.raquo.laminar.api.L.*
import frontroute.LinkHandler
import org.scalajs.dom
import anseliq.routing.Routes
import anseliq.state.SessionStore
import anseliq.view.layout.MainLayout

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("WebApp")
object WebApp:
  def main(args: Array[String]): Unit =
    SessionStore.restore()
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      MainLayout(
        div(
          LinkHandler.bind,
          Routes()
        )
      )
    )
