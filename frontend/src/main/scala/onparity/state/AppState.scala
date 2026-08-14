package onparity.state

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import onparity.common.model.FundFlowPayload

object AppState:
  val darkMode:       Var[Boolean]                 = Var(dom.window.localStorage.getItem("darkMode") == "true")
  val globalError:    Var[Option[String]]          = Var(None)
  val fundFlows:      Var[Seq[FundFlowPayload]]     = Var(Seq.empty)
  val activeFundFlow: Var[Option[FundFlowPayload]]  = Var(None)

  def toggleDarkMode(): Unit =
    darkMode.update(!_)
    dom.window.localStorage.setItem("darkMode", darkMode.now().toString)
