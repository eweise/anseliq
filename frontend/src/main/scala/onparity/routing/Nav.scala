package onparity.routing

import frontroute.BrowserNavigation
import org.scalajs.dom
import onparity.common.model.FundFlowPayload
import onparity.state.AppState

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

  def toMaintainReconSpec(accountId: String, reconSpecId: String = "new"): Unit =
    push(s"/components/accounts/$accountId/maintain-recon-spec/$reconSpecId")

  def toIngest(accountId: String, reconSpecId: String): Unit =
    push(s"/components/accounts/$accountId/ingest/$reconSpecId")

  def toReconciliations(accountId: String, reconSpecId: String): Unit =
    push(s"/components/accounts/$accountId/reconciliations/$reconSpecId")

  def toManualMatch(accountId: String, reconSpecId: String): Unit =
    push(s"/components/accounts/$accountId/manual-match/$reconSpecId")

  def toMaintainMovementSpec(outAccountId: String, inAccountId: String, id: String = "new"): Unit =
    push(s"/components/maintain-movement-spec/$outAccountId/$inAccountId/$id")

  def toJobs(): Unit = push("/components/jobs")

  def toUnreconciledMovements(accountId: String): Unit =
    push(s"/components/accounts/$accountId/unreconciled-movements")

  def toAllUnreconciledMovements(): Unit = push("/components/unreconciled-movements")

  def toExceptionDetail(id: String): Unit = push(s"/components/exceptions/$id")

  def toReconciliationDetail(id: String): Unit = push(s"/components/reconciliations/$id")

  def toAccountReconciliations(accountId: String): Unit =
    push(s"/components/accounts/$accountId/recent-reconciliations")

  def toMovementDetail(id: String): Unit = push(s"/components/movements/$id")

  def toTestParser(movementSpecId: String): Unit =
    push(s"/components/movement-specs/$movementSpecId/test-parse")

  def toUsers(): Unit = push("/components/users")

  def toMaintainUser(userId: String = "new"): Unit =
    push(s"/components/maintain-user/$userId")
