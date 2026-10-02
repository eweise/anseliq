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

  // Same page as toFundFlow — the tab lives in the query string so switching tabs adds a history
  // entry and the back button steps between tabs (see FundFlowPage.tabFromUrl).
  def toFundFlowTab(tab: String, status: Option[String] = None): Unit = push(fundFlowTabUrl(tab, status))

  // Opens a ledger straight onto one tab — e.g. a reconciliation's breadcrumb going back to that
  // ledger's Reconciliations tab. `status` pre-filters the Reconciliations tab.
  def toFundFlow(ff: FundFlowPayload, tab: String, status: Option[String]): Unit =
    AppState.activeFundFlow.set(Some(ff))
    push(fundFlowTabUrl(tab, status))

  def fundFlowTabUrl(tab: String, status: Option[String] = None): String =
    s"/components/fund-flow?tab=$tab" + status.filter(_.nonEmpty).fold("")(s => s"&status=$s")

  def toMaintainFundFlow(): Unit =
    push("/components/maintain-fund-flow")

  def toMaintainAccount(accountId: Option[String] = None): Unit =
    accountId match
      case Some(id) => push(s"/components/accounts/$id/maintain")
      case None     => push("/components/accounts/new/maintain")

  def toManualMatch(accountId: String, reconSpecId: String): Unit =
    push(s"/components/accounts/$accountId/manual-match/$reconSpecId")

  def toMaintainMovementSpec(outAccountId: String, inAccountId: String, id: String = "new"): Unit =
    push(s"/components/maintain-movement-spec/$outAccountId/$inAccountId/$id")

  def toFileFeeds(): Unit = push("/components/file-feeds")

  def toMaintainFileFeed(id: String = "new"): Unit =
    push(s"/components/maintain-file-feed/$id")

  def toJobs(): Unit = push("/components/jobs")

  def toAllUnreconciledMovements(): Unit = push("/components/unreconciled-movements")

  def toExceptionDetail(id: String): Unit = push(s"/components/exceptions/$id")

  def toReconciliationDetail(id: String): Unit = push(s"/components/reconciliations/$id")


  def toMovementDetail(id: String): Unit = push(s"/components/movements/$id")

  def toTestParser(fileFeedId: String): Unit =
    push(s"/components/file-feeds/$fileFeedId/test-parse")

  def toUsers(): Unit = push("/components/users")

  def toMaintainUser(userId: String = "new"): Unit =
    push(s"/components/maintain-user/$userId")

  // Opened from a File Inventory row with that file's name filled in (and previewed).
  def toFixBadFile(fileName: String): Unit =
    push(s"/components/fix-bad-file?file=${scala.scalajs.js.URIUtils.encodeURIComponent(fileName)}")
