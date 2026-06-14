package perfectpair.state

import com.raquo.laminar.api.L.*
import perfectpair.common.model.{FundFlowPayload, MovementSpecPayload, ReconciliationSpecPayload}

object AppState:
  val darkMode:         Var[Boolean]                           = Var(false)
  val globalError:      Var[Option[String]]                    = Var(None)
  val fundFlowNavOpen:  Var[Boolean]                           = Var(false)
  val fundFlows:        Var[Seq[FundFlowPayload]]              = Var(Seq.empty)
  val activeFundFlow:   Var[Option[FundFlowPayload]]           = Var(None)
  val editMovementSpec: Var[Option[MovementSpecPayload]]       = Var(None)
  val editReconSpec:    Var[Option[ReconciliationSpecPayload]] = Var(None)
