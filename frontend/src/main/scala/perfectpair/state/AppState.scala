package perfectpair.state

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import perfectpair.common.model.{FundFlowPayload, MovementSpecPayload, ReconciliationSpecPayload}

object AppState:
  val darkMode:         Var[Boolean]                           = Var(dom.window.localStorage.getItem("darkMode") == "true")
  val globalError:      Var[Option[String]]                    = Var(None)
  val fundFlows:        Var[Seq[FundFlowPayload]]              = Var(Seq.empty)
  val activeFundFlow:   Var[Option[FundFlowPayload]]           = Var(None)
  val editMovementSpec: Var[Option[MovementSpecPayload]]       = Var(None)
  val editReconSpec:    Var[Option[ReconciliationSpecPayload]] = Var(None)

  def toggleDarkMode(): Unit =
    darkMode.update(!_)
    dom.window.localStorage.setItem("darkMode", darkMode.now().toString)
