package onparity.main

import com.raquo.laminar.api.L.*
import frontroute.LinkHandler
import org.scalajs.dom
import onparity.routing.Routes
import onparity.state.SessionStore
import onparity.view.layout.MainLayout

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
