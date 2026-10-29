package anseliq.routing

import frontroute.BrowserNavigation
import org.scalajs.dom
import anseliq.common.model.LedgerPayload
import anseliq.state.AppState

object Nav:
  def push(url: String): Unit = BrowserNavigation.pushState(url = url)

  def back(): Unit = dom.window.history.back()

  def toLedgers(): Unit = push("/components/ledgers")

  def toLedger(ledger: LedgerPayload): Unit =
    AppState.activeLedger.set(Some(ledger))
    push("/components/ledger")

  // Same page as toLedger — the tab lives in the query string so switching tabs adds a history
  // entry and the back button steps between tabs (see LedgerPage.tabFromUrl).
  def toLedgerTab(tab: String, status: Option[String] = None): Unit = push(ledgerTabUrl(tab, status))

  // Opens a ledger straight onto one tab — e.g. a reconciliation's breadcrumb going back to that
  // ledger's Reconciliations tab. `status` pre-filters the Reconciliations tab.
  def toLedger(ledger: LedgerPayload, tab: String, status: Option[String]): Unit =
    AppState.activeLedger.set(Some(ledger))
    push(ledgerTabUrl(tab, status))

  def ledgerTabUrl(tab: String, status: Option[String] = None): String =
    s"/components/ledger?tab=$tab" + status.filter(_.nonEmpty).fold("")(s => s"&status=$s")

  def toMaintainLedger(): Unit =
    push("/components/maintain-ledger")

  def toManualMatch(accountId: String, reconSpecId: String): Unit =
    push(s"/components/accounts/$accountId/manual-match/$reconSpecId")

  def toMaintainFileFeed(id: String = "new"): Unit =
    push(s"/components/maintain-file-feed/$id")

  def toAllUnreconciledMovements(): Unit = push("/components/unreconciled-movements")

  def toExceptionDetail(id: String): Unit = push(s"/components/exceptions/$id")

  def toReconciliationDetail(id: String): Unit = push(s"/components/reconciliations/$id")

  def toMovementDetail(id: String): Unit = push(s"/components/movements/$id")

  def toTestParser(fileFeedId: String): Unit =
    push(s"/components/file-feeds/$fileFeedId/test-parse")

  // Opened from a File Inventory row with that file's name filled in (and previewed).
  def toFixBadFile(fileName: String): Unit =
    push(s"/components/fix-bad-file?file=${scala.scalajs.js.URIUtils.encodeURIComponent(fileName)}")
