package perfectpair.routing

import frontroute.BrowserNavigation
import org.scalajs.dom
import perfectpair.common.model.FundFlowPayload
import perfectpair.state.AppState

object Nav:
  def push(url: String): Unit = BrowserNavigation.pushState(url = url)

  def back(): Unit = dom.window.history.back()

  def toFundFlows(): Unit = push("/components/fund-flows")

  def toFundFlow(ff: FundFlowPayload): Unit =
    AppState.activeFundFlow.set(Some(ff))
    push("/components/fund-flow")

  def toMaintainFundFlow(): Unit =
    push("/components/maintain-fund-flow")

  def toMaintainAccount(accountId: Option[String] = None): Unit =
    accountId match
      case Some(id) => push(s"/components/accounts/$id/maintain")
      case None     => push("/components/accounts/new/maintain")

  def toReconSpecs(accountId: String): Unit =
    push(s"/components/accounts/$accountId/recon-specs")

  def toMaintainReconSpec(accountId: String): Unit =
    push(s"/components/accounts/$accountId/maintain-recon-spec")

  def toIngest(accountId: String): Unit =
    push(s"/components/accounts/$accountId/ingest")

  def toReconciliations(accountId: String): Unit =
    push(s"/components/accounts/$accountId/reconciliations")

  def toManualMatch(accountId: String): Unit =
    push(s"/components/accounts/$accountId/manual-match")

  def toMaintainMovementSpec(): Unit =
    push("/components/maintain-movement-spec")

  def toJobs(): Unit = push("/components/jobs")

  def toUnreconciledMovements(accountId: String): Unit =
    push(s"/components/accounts/$accountId/unreconciled-movements")
