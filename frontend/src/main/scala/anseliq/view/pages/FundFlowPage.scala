package anseliq.view.pages

import com.raquo.laminar.api.L.*
import frontroute.BrowserNavigation
import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.*
import upickle.default.*

import scala.collection.mutable

object FundFlowPage:

  private val tabs = Set("Overview", "Reconciliations", "Movements", "Setup")
  private val boxW      = 140
  private val boxH      = 52
  private val colGap    = 100
  private val rowGap    = 30
  private val margin    = 30
  private val colStride = boxW + colGap
  private val rowStride = boxH + rowGap

  def apply(): HtmlElement =
    AppState.activeFundFlow.now() match
      case None     => div(onMountCallback(_ => Nav.toFundFlows()))
      case Some(ff) => renderPage(ff)

  private def renderPage(ff: FundFlowPayload): HtmlElement =
    val ffId      = ff.id.getOrElse("")
    val ffVar     = Var(ff)
    val state     = Var(State())
    val activeTab   = Var(tabFromUrl())
    val reconStatus = Var(statusFromUrl())
    val selectTab = (tab: String) =>
      if activeTab.now() != tab then
        activeTab.set(tab)
        Nav.toFundFlowTab(tab)
    // Opens the Reconciliations tab filtered to one status ("" = all), as its own history entry.
    val openReconciliations = (status: String) =>
      reconStatus.set(status)
      if activeTab.now() != "Reconciliations" then activeTab.set("Reconciliations")
      Nav.toFundFlowTab("Reconciliations", Some(status))
    // A filter chip on the tab itself: replaces the URL rather than adding history, so Back still
    // leaves the tab instead of stepping through every chip clicked.
    val selectReconStatus = (status: String) =>
      reconStatus.set(status)
      BrowserNavigation.replaceState(url = Nav.fundFlowTabUrl("Reconciliations", Some(status)), popStateEvent = false)
    val configureReconBus = EventBus[AccountPayload]()
    val manualMatchBus    = EventBus[AccountPayload]()
    val deleteSpecBus     = EventBus[MovementSpecPayload]()
    val reconcileBus      = EventBus[String]()
    val saveFundFlowBus   = EventBus[Unit]()
    // Fired when the account/movement-spec modal closes, to pick up whatever it just created or
    // edited — re-fetches both lists since either could have changed either way.
    val refreshBus = EventBus[Unit]()
    val closeModal = () => {
      state.update(_.copy(editingAccountId = None, editingMovementSpec = None, editingReconSpec = None, ingesting = false))
      refreshBus.emit(())
    }

    PageHeader.page(
      saveFundFlowBus.events.flatMapSwitch { _ =>
        ApiClient.put[FundFlowPayload, FundFlowPayload](
          HttpPath.url(s"${HttpPath.fundFlow}/$ffId"),
          ffVar.now(),
          SessionStore.token
        )
      }.apiBind { updated =>
        ffVar.set(updated)
        AppState.activeFundFlow.set(Some(updated))
      },
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token)
        .apiBind(accounts => state.update(_.copy(accounts = accounts))),
      ApiClient
        .get[FundFlowSummaryViewPayload](
          HttpPath.url(s"${HttpPath.fundFlow}/$ffId/summary"),
          SessionStore.token
        )
        .apiBind { summary =>
          val summaries = summary.accounts.flatMap(a => a.account.id.map(_ -> a)).toMap
          state.update(_.copy(summaries = summaries))
        },
      ApiClient
        .get[Seq[MovementSpecPayload]](
          HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
          SessionStore.token
        )
        .apiBind(specs => state.update(_.copy(movementSpecs = specs))),
      ApiClient
        .get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        .apiBind(feeds => state.update(_.copy(fileFeeds = feeds))),
      refreshBus.events.flatMapSwitch { _ =>
        ApiClient.get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token)
      }.apiBind(accounts => state.update(_.copy(accounts = accounts))),
      refreshBus.events.flatMapSwitch { _ =>
        ApiClient.get[FundFlowSummaryViewPayload](HttpPath.url(s"${HttpPath.fundFlow}/$ffId/summary"), SessionStore.token)
      }.apiBind { summary =>
        state.update(_.copy(summaries = summary.accounts.flatMap(a => a.account.id.map(_ -> a)).toMap))
      },
      refreshBus.events.flatMapSwitch { _ =>
        ApiClient.get[Seq[MovementSpecPayload]](
          HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
          SessionStore.token
        )
      }.apiBind(specs => state.update(_.copy(movementSpecs = specs))),
      child.maybe <-- state.signal.map { s =>
        s.editingAccountId
          .map(id => Modal(closeModal, MaintainAccountPage(id, closeModal)))
          .orElse(s.editingMovementSpec.map { case (out, in, id) =>
            Modal(closeModal, MaintainMovementSpecPage(out, in, id, closeModal))
          })
          .orElse(s.editingReconSpec.map((accountId, specId) =>
            Modal(closeModal, MaintainReconSpecPage(accountId, specId, closeModal))
          ))
          .orElse(Option.when(s.ingesting)(Modal(closeModal, IngestDialog(s.movementSpecs, closeModal))))
      },
      configureReconBus.events.flatMapSwitch { account =>
        ApiClient
          .get[Seq[ReconciliationSpecPayload]](
            HttpPath.url(s"${HttpPath.account}/${account.id.get}/recon-specs"),
            SessionStore.token
          )
          .map(res => (account, res))
      } --> {
        case (account, Right(specs)) =>
          val reconSpecId = specs.headOption.flatMap(_.id).getOrElse("new")
          state.update(_.copy(selectedAccount = None, editingReconSpec = Some(account.id.get -> reconSpecId)))
        case (_, Left(e)) => AppState.globalError.set(Some(e))
      },
      // Shortcut straight to Manual Match for an account whose recon spec already exists — skips
      // the "Configure Reconciliation" detour through MaintainReconSpecPage's edit form, which is
      // otherwise the only way to reach it. No existing spec yet: fall back to the same "configure
      // first" flow, since there's nothing to manually match against.
      manualMatchBus.events.flatMapSwitch { account =>
        ApiClient
          .get[Seq[ReconciliationSpecPayload]](
            HttpPath.url(s"${HttpPath.account}/${account.id.get}/recon-specs"),
            SessionStore.token
          )
          .map(res => (account, res))
      } --> {
        case (account, Right(specs)) =>
          specs.headOption.flatMap(_.id) match
            case Some(reconSpecId) => Nav.toManualMatch(account.id.get, reconSpecId)
            case None              => state.update(_.copy(editingReconSpec = Some(account.id.get -> "new")))
        case (_, Left(e)) => AppState.globalError.set(Some(e))
      },
      deleteSpecBus.events.flatMapSwitch { spec =>
        ApiClient.delete(HttpPath.url(s"${HttpPath.movementSpec}/${spec.id.get}"), SessionStore.token)
      }.flatMapSwitch {
        case Right(_) =>
          ApiClient.get[Seq[MovementSpecPayload]](
            HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
            SessionStore.token
          )
        case Left(e) =>
          AppState.globalError.set(Some(e))
          EventStream.empty
      }.apiBind(specs => state.update(_.copy(movementSpecs = specs))),
      // flatMapMerge, not flatMapSwitch: "Run reconciliation" emits once per recon account, and
      // each run has to finish rather than being cancelled by the next account's.
      reconcileBus.events.map { id =>
        state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.InProgress)))
        id
      }.flatMapMerge { id =>
        ApiClient
          .postEmpty[ujson.Value](HttpPath.url(s"${HttpPath.account}/$id/reconcile"), SessionStore.token)
          .map(res => (id, res))
      } --> {
        case (id, Right(_)) =>
          state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Success)))
          refreshBus.emit(())
        case (id, Left(e)) =>
          state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Failed(e))))
      },
      // Back/forward between ?tab= entries. frontroute's pushState also dispatches a synthetic
      // popstate, so this fires on selectTab's own push too — and Airstream signals don't dedupe
      // equal values, so re-setting the same tab would rebuild it (wiping the Movements tab's
      // pre-filled, already-run search). Only set it when the URL's tab actually differs.
      windowEvents(_.onPopState).map(_ => tabFromUrl()).filter(_ != activeTab.now()) --> activeTab.writer,
      windowEvents(_.onPopState).map(_ => statusFromUrl()).filter(_ != reconStatus.now()) --> reconStatus.writer,
      PageHeader.titleRow(
        PageHeader.breadcrumb("Ledgers" -> Some(Observer[Unit](_ => Nav.toFundFlows())), ff.name -> None),
        ledgerHeader(ffVar.signal.map(_.name), state, reconcileBus, manualMatchBus)
      ),
      div(
        cls := "w-full border-b border-gray-200 dark:border-gray-700 mb-6",
        div(
          cls := "w-full px-8 flex -mb-px space-x-8",
          tabButton("Overview", "Overview", activeTab.signal, selectTab),
          tabButton("Reconciliations", "Reconciliations", activeTab.signal, selectTab),
          tabButton("Movements", "Movements", activeTab.signal, selectTab),
          tabButton("Setup", "Setup", activeTab.signal, selectTab)
        )
      ),
      div(
        cls := "w-full px-8",
        child <-- activeTab.signal.map {
          case "Setup" =>
            div(
              cls := "flex flex-col gap-6",
              div(
                cls := s"${Card.cls} p-6",
                h3(cls := "font-medium mb-4 text-md-on-surface dark:text-white", "Ledger Settings"),
                div(
                  // items-center rather than items-end: Form.field/numberField each carry their
                  // own mb-5, but the Save button doesn't, so end-aligning would visibly offset
                  // the button below the inputs instead of lining up with them.
                  cls := "flex items-center gap-4 flex-wrap",
                  div(
                    cls := "flex-1 max-w-sm",
                    Form.field("ffName", "Name", ff.name, v => ffVar.update(_.copy(name = v)))
                  ),
                  Form.numberField(
                    "ffDailyCutoffHours",
                    "Cutoff Hour (0-23)",
                    ff.dailyCutoffHours,
                    0,
                    23,
                    v => ffVar.update(_.copy(dailyCutoffHours = v))
                  ),
                  Buttons.linkButton("Save Ledger", Observer[Unit](_ => saveFundFlowBus.emit(())))
                )
              ),
              child <-- state.signal.map(_.accounts.filter(_.accountType == AccountTypePayload.Recon)).distinct.map {
                reconAccounts => reconRulesCard(reconAccounts, configureReconBus)
              },
              div(
                cls := s"${Card.cls} p-6",
                div(
                  cls := "flex items-center justify-between mb-4",
                  h3(cls := "font-medium text-md-on-surface dark:text-white", "Accounts"),
                  Buttons.linkButton(
                    "New Account",
                    Observer[Unit](_ =>
                      state.update(
                        _.copy(selectedAccount = None, selectedMovementSpec = None, editingAccountId = Some("new"))
                      )
                    )
                  )
                ),
                child <-- state.signal.map { s =>
                  if s.accounts.isEmpty then div(cls := "text-gray-500 dark:text-gray-400 text-sm", "No accounts")
                  else renderDiagram(s, ff, configureReconBus, manualMatchBus, deleteSpecBus, state)
                }
              )
            )
          case "Overview" =>
            div(
              child <-- state.signal.map { s =>
                if s.accounts.isEmpty then div(cls := "text-gray-500 dark:text-gray-400 text-sm", "No accounts")
                else
                  val (reconAccounts, otherAccounts) =
                    s.accounts.sortBy(_.name).partition(_.accountType == AccountTypePayload.Recon)
                  div(
                    cls := "flex flex-col gap-6",
                    reconAccounts.map { account =>
                      val accountId = account.id.getOrElse("")
                      reconciliationCard(
                        account,
                        s.summaries.get(accountId),
                        // Jumps to the Movements tab with the account and "Unreconciled"
                        // pre-filled and the search already run — see searchMovementsTab's
                        // pendingSearch handling.
                        onUnreconciled = Observer[Unit](_ =>
                          state.update(
                            _.copy(pendingSearch = Some(accountId -> ReconciliationFilterPayload.Unreconciled))
                          )
                          selectTab("Movements")
                        ),
                        onReconciliations = Observer[Unit](_ => openReconciliations("")),
                        onInProgress      = Observer[Unit](_ => openReconciliations("InProgress")),
                        onMismatched      = Observer[Unit](_ => openReconciliations("Mismatched")),
                        // Fetches the account's recon spec on click, since the card doesn't
                        // otherwise know its spec id — see manualMatchBus.
                        onManualMatch = Observer[Unit](_ => manualMatchBus.emit(account))
                      )
                    },
                    if otherAccounts.isEmpty then emptyNode else accountsCard(otherAccounts)
                  )
              }
            )
          case "Reconciliations" =>
            div(
              child <-- state.signal.map(_.accounts.filter(_.accountType == AccountTypePayload.Recon)).distinct.map {
                reconAccounts => LedgerReconciliationsTab(reconAccounts, reconStatus.signal, selectReconStatus)
              }
            )
          case "Movements" => searchMovementsTab(ffId, state)
          case _        => div()
        }
      )
    )

  // The active tab comes from ?tab= so each switch is its own history entry. frontroute only
  // re-renders a route when its matched path changes, not its query string, so changing tabs keeps
  // this page (and its State) mounted.
  private def tabFromUrl(): String =
    Option(new org.scalajs.dom.URLSearchParams(org.scalajs.dom.window.location.search).get("tab"))
      .map {
        case "Search"        => "Movements" // the tabs' earlier names, so old links still land
        case "Configuration" => "Setup"
        case other           => other
      }
      .filter(tabs.contains)
      .getOrElse("Overview")

  // The Reconciliations tab's status filter; "" (all) when absent or not a ReconciliationStatus.
  private def statusFromUrl(): String =
    Option(new org.scalajs.dom.URLSearchParams(org.scalajs.dom.window.location.search).get("status"))
      .filter(s => ReconStatus.filters.exists(_._1 == s))
      .getOrElse("")

  // A recon account's cash balance always splits exactly into unreconciled + in progress +
  // mismatched: every Final reconciliation nets to zero (a write-off makes one Final), so whatever
  // isn't unreconciled is sitting in an InProgress or Mismatched one. The two reconciled figures
  // come from the summary endpoint (see AccountSummaryViewPayload), so they read "…" until it loads.
  private def reconciliationCard(
    account:           AccountPayload,
    summary:           Option[AccountSummaryViewPayload],
    onUnreconciled:    Observer[Unit],
    onReconciliations: Observer[Unit],
    onInProgress:      Observer[Unit],
    onMismatched:      Observer[Unit],
    onManualMatch:     Observer[Unit]
  ): HtmlElement =
    def plural(n: Int, word: String) = s"$n $word${if n == 1 then "" else "s"}"
    div(
      cls := s"${Card.cls} p-6",
      div(
        cls := "flex flex-wrap items-start justify-between gap-4",
        div(
          div(
            cls := "flex items-center gap-2",
            h3(cls := "font-medium text-md-on-surface dark:text-white", account.name),
            typePill(account.accountType)
          ),
          div(cls := s"${Form.sectionLabelCls} mt-4", "Cash balance"),
          div(
            cls := "mt-1 text-3xl font-semibold tabular-nums text-md-on-surface dark:text-white",
            money(account.balance)
          )
        ),
        div(
          cls := "flex items-center gap-2",
          Buttons.textButton("Reconciliations", onReconciliations),
          Buttons.textButton("Manual Match", onManualMatch)
        )
      ),
      div(
        cls := "mt-6 grid grid-cols-1 sm:grid-cols-3 gap-4",
        balanceTile(
          "Unreconciled",
          money(account.unreconciledBalance),
          summary.map(s => plural(s.unreconciledCount, "movement")),
          Some(onUnreconciled)
        ),
        balanceTile(
          "In progress",
          summary.fold("…")(s => money(s.inProgressBalance)),
          summary.map(s => plural(s.inProgressCount, "reconciliation")),
          Some(onInProgress)
        ),
        balanceTile(
          "Mismatched",
          summary.fold("…")(s => money(s.mismatchedBalance)),
          summary.map(s => plural(s.mismatchedCount, "reconciliation")),
          Some(onMismatched)
        )
      ),
      p(
        cls := s"mt-4 text-xs ${Form.mutedTextCls}",
        "Cash balance = unreconciled + in progress + mismatched. Final reconciliations net to zero."
      )
    )

  private def balanceTile(
    label:  String,
    amount: String,
    detail: Option[String],
    onOpen: Option[Observer[Unit]]
  ): HtmlElement =
    val content = Seq(
      div(cls := Form.sectionLabelCls, label),
      div(cls := "mt-1 text-xl font-semibold tabular-nums text-md-on-surface dark:text-white", amount),
      div(cls := s"mt-1 text-sm ${Form.mutedTextCls}", detail.getOrElse(""))
    )
    val base = "rounded-lg bg-md-surface-variant dark:bg-md-surface-alt-dark p-4 text-left"
    onOpen match
      case Some(obs) =>
        button(
          typ := "button",
          cls := s"$base hover:ring-2 hover:ring-md-primary transition-shadow cursor-pointer",
          onClick.mapToUnit --> obs,
          content
        )
      case None => div(cls := base, content)

  private def accountsCard(accounts: Seq[AccountPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-6",
      h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "Accounts"),
      div(
        cls := "divide-y divide-md-outline-variant dark:divide-gray-700",
        accounts.map { account =>
          div(
            cls := "flex items-center justify-between gap-4 py-3",
            div(
              cls := "flex items-center gap-2",
              span(cls := "text-md-on-surface dark:text-white", account.name),
              typePill(account.accountType)
            ),
            span(cls := "tabular-nums text-md-on-surface dark:text-white", money(account.balance))
          )
        }
      )
    )

  private def money(amount: BigDecimal): String =
    if amount < 0 then f"-$$${-amount}%.2f" else f"$$${amount}%.2f"

  private def typePill(accountType: AccountTypePayload): HtmlElement = accountType match
    case AccountTypePayload.Recon     => Pill("Recon", Pill.Tone.Accent)
    case AccountTypePayload.Primary   => Pill("Primary", Pill.Tone.Good)
    case AccountTypePayload.Secondary => Pill("Secondary", Pill.Tone.Neutral)

  // Title plus the ledger-wide actions that used to be unreachable or buried: Run reconciliation
  // (the endpoint and reconcileBus existed but nothing triggered them), Ingest file (only reachable
  // from deep inside a recon spec's pages) and Manual match.
  private def ledgerHeader(
    name:           Signal[String],
    state:          Var[State],
    reconcileBus:   EventBus[String],
    manualMatchBus: EventBus[AccountPayload]
  ): HtmlElement =
    val reconAccounts = state.signal.map(_.accounts.filter(_.accountType == AccountTypePayload.Recon))
    div(
      cls := "flex flex-wrap items-center justify-between gap-4 mb-6",
      h2(cls := "text-2xl font-medium text-gray-900 dark:text-white", child.text <-- name),
      div(
        cls := "flex flex-wrap items-center gap-2",
        child <-- state.signal.map(s => runStatusText(s.reconcileStatuses)),
        child <-- reconAccounts.map(_.nonEmpty).distinct.map { hasRecon =>
          if !hasRecon then emptyNode
          else
            Buttons.linkButton(
              "Run reconciliation",
              Observer[Unit](_ =>
                state.now().accounts.filter(_.accountType == AccountTypePayload.Recon).flatMap(_.id).foreach(reconcileBus.emit)
              )
            )
        },
        Buttons.textButton("Ingest file", Observer[Unit](_ => state.update(_.copy(ingesting = true)))),
        child <-- reconAccounts.map(_.headOption).distinct.map {
          case None          => emptyNode
          case Some(account) => Buttons.textButton("Manual match", Observer[Unit](_ => manualMatchBus.emit(account)))
        }
      )
    )

  private def runStatusText(statuses: Map[String, ReconcileStatus]): HtmlElement =
    val failures = statuses.values.collect { case ReconcileStatus.Failed(msg) => msg }
    val text =
      if statuses.values.exists(_ == ReconcileStatus.InProgress) then Some("Reconciling…" -> Form.mutedTextCls)
      else if failures.nonEmpty then Some(s"Reconciliation failed: ${failures.head}" -> "text-md-critical dark:text-md-critical-dark")
      else if statuses.nonEmpty then Some("Reconciled just now" -> "text-md-good dark:text-md-good-dark")
      else None
    text.map((t, tone) => span(cls := s"text-sm $tone mr-2", t)).getOrElse(span())

  // One row per recon account: its rule at a glance, with Edit opening the rule form in a Modal.
  private def reconRulesCard(reconAccounts: Seq[AccountPayload], configureReconBus: EventBus[AccountPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-6 flex flex-col gap-4",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Reconciliation rules"),
      if reconAccounts.isEmpty then
        p(cls := s"text-sm ${Form.mutedTextCls}", "Add a Recon account below, then set up how its movements are matched.")
      else
        div(
          cls := "flex flex-col divide-y divide-md-outline-variant dark:divide-gray-700",
          reconAccounts.map { account =>
            val specs = Var(Option.empty[Seq[ReconciliationSpecPayload]])
            div(
              cls := "flex flex-wrap items-center justify-between gap-3 py-3",
              ApiClient
                .get[Seq[ReconciliationSpecPayload]](
                  HttpPath.url(s"${HttpPath.account}/${account.id.getOrElse("")}/recon-specs"),
                  SessionStore.token
                )
                .apiBind(ss => specs.set(Some(ss))),
              div(
                div(cls := "font-medium text-md-on-surface dark:text-white", account.name),
                div(
                  cls := s"text-sm ${Form.mutedTextCls}",
                  child.text <-- specs.signal.map {
                    case None => "Loading…"
                    case Some(ss) =>
                      ss.headOption.fold("Not set up yet") { spec =>
                        s"${spec.relationship} · matched on ${ruleText(spec.rule)} · flagged after ${spec.agingLimit} day${if spec.agingLimit == 1 then "" else "s"}"
                      }
                  }
                )
              ),
              child <-- specs.signal.map { ss =>
                val label = if ss.exists(_.nonEmpty) then "Edit rule" else "Set up"
                Buttons.textButton(label, Observer[Unit](_ => configureReconBus.emit(account)))
              }
            )
          }
        )
    )

  // A recon rule as the condition(s) it matches on, e.g. "transaction_id = txn_id".
  private def ruleText(rule: Rule): String = rule match
    case AndRule(rules) => rules.map(ruleText).mkString(" and ")
    case ORRule(rules)  => rules.map(ruleText).mkString(" or ")
    case NoOpRule       => "no rule yet"
    case leaf           => RuleMatch(leaf, "").condition

  private def tabButton(
    label:     String,
    tabId:     String,
    activeTab: Signal[String],
    selectTab: String => Unit
  ): HtmlElement =
    button(
      cls <-- activeTab.map { active =>
        val base = "px-4 pb-3 text-sm font-medium border-b-2 transition-colors"
        if active == tabId then s"$base border-gray-900 text-gray-900 dark:border-white dark:text-white"
        else
          s"$base border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-200 dark:hover:border-gray-500"
      },
      onClick --> (_ => selectTab(tabId)),
      label
    )

  // Kept local to this tab (rebuilt fresh whenever the user switches back to it) rather than
  // folded into the page-level State — none of the other tabs need this data, and it keeps the
  // shared State from growing ten fields that only ever matter here. The one exception is
  // pendingSearch: the Overview tab's Unreconciled tile switches to this tab and stash the account and filter there so it can be consumed once, right here, to
  // seed a pre-filled, auto-run search — that hand-off has to go through the shared State since
  // this tab's own searchVar doesn't exist yet at the moment those links are clicked.
  private def searchMovementsTab(ffId: String, state: Var[State]): HtmlElement =
    val prefill = state.now().pendingSearch
    if prefill.isDefined then state.update(_.copy(pendingSearch = None))
    val searchVar = Var(
      prefill.fold(SearchState())((accountId, filter) => SearchState(accountId = accountId, reconciliation = filter))
    )
    val searchBus = EventBus[Unit]()

    val autoSearchTrigger =
      if prefill.isDefined then EventStream.fromValue(()) else EventStream.empty

    div(
      EventStream.merge(autoSearchTrigger, searchBus.events).flatMapSwitch { _ =>
        val s = searchVar.now()
        val req = MovementSearchRequest(
          fundFlowId      = ffId,
          accountId       = Option(s.accountId).filter(_.nonEmpty),
          amount          = scala.util.Try(BigDecimal(s.amount)).toOption,
          movementSpecId  = Option(s.movementSpecId).filter(_.nonEmpty),
          properties      = s.properties.filter(_._2.nonEmpty),
          fileSourceQuery = Option(s.fileSourceFilename).filter(_.nonEmpty),
          reconciliation  = s.reconciliation,
          createdFrom     = parseDateStart(s.createdFrom),
          createdTo       = parseDateEndExclusive(s.createdTo),
          movementType   = Option(s.movementType).filter(_.nonEmpty),
          adjustmentType =
            if s.movementType == "ManualAdjustment" then AdjustmentTypePayload.values.find(_.toString == s.adjustmentType)
            else None
        )
        ApiClient.post[Seq[MovementSearchResultPayload], MovementSearchRequest](
          HttpPath.url(s"${HttpPath.movements}/search"),
          req,
          SessionStore.token
        )
      } --> {
        case Right(results) => searchVar.update(_.copy(results = Some(results), searchError = None))
        case Left(err)      => searchVar.update(_.copy(searchError = Some(err), results = None))
      },
      div(
        cls := s"${Card.cls} p-6 mb-6",
        h3(cls := "font-medium mb-4 text-md-on-surface dark:text-white", "Search Movements"),
        div(
          cls := "grid grid-cols-1 md:grid-cols-3 gap-4",
          child <-- state.signal.map(_.accounts).map { accounts =>
            Form.selectField(
              "searchAccount",
              "Account Name",
              ("", "Any") +: accounts.sortBy(_.name).map(a => a.id.getOrElse("") -> a.name),
              searchVar.now().accountId,
              v => searchVar.update(_.copy(accountId = v))
            )
          },
          Form.field(
            "searchAmount",
            "Amount",
            searchVar.now().amount,
            v => searchVar.update(_.copy(amount = v)),
            inputType = "number"
          ),
          child <-- state.signal.map(_.movementSpecs).map { specs =>
            Form.selectField(
              "searchMovementSpec",
              "Movement Spec Name",
              ("", "Any") +: specs.sortBy(_.name).map(s => s.id.getOrElse("") -> s.name),
              searchVar.now().movementSpecId,
              // Clears any previously-entered property filters — they belong to whichever spec
              // was selected before, and that spec's keys may not even apply to the new one.
              v => searchVar.update(_.copy(movementSpecId = v, properties = Map.empty))
            )
          },
          Form.selectField(
            "searchMovementType",
            "Type",
            Seq(
              ""                 -> "Any",
              "Ingestion"        -> "Ingestion",
              "ManualAdjustment" -> "Manual adjustment",
              "Reversal"         -> "Reversal"
            ),
            searchVar.now().movementType,
            // Leaving Manual adjustment clears any adjustment type, since that filter is hidden
            // (and meaningless) for every other type.
            v =>
              searchVar.update(s =>
                s.copy(movementType = v, adjustmentType = if v == "ManualAdjustment" then s.adjustmentType else "")
              )
          ),
          // What a manual adjustment was posted for (e.g. write-offs). Only manual adjustments have
          // one, so it's shown only once Type is Manual adjustment — the same reveal-on-select as
          // the spec-scoped Filename/Properties fields below.
          child <-- searchVar.signal.map(_.movementType == "ManualAdjustment").distinct.map { isAdjustment =>
            if !isAdjustment then emptyNode
            else
              Form.selectField(
                "searchAdjustmentType",
                "Adjustment Type",
                ("" -> "Any") +: AdjustmentTypePayload.values.toSeq.map(t => t.toString -> t.label),
                searchVar.now().adjustmentType,
                v => searchVar.update(_.copy(adjustmentType = v))
              )
          },
          // Kept as one unit (its own mini-grid) rather than two separate top-level grid items, so
          // the pair always renders side by side regardless of how the outer 3-column grid wraps.
          div(
            cls := "grid grid-cols-2 gap-3",
            Form.field(
              "searchCreatedFrom",
              "Created From",
              searchVar.now().createdFrom,
              v => searchVar.update(_.copy(createdFrom = v)),
              inputType = "date"
            ),
            Form.field(
              "searchCreatedTo",
              "Created To",
              searchVar.now().createdTo,
              v => searchVar.update(_.copy(createdTo = v)),
              inputType = "date"
            )
          )
        ),
        // The file-source Filename field and the Properties keys are both scoped to whichever
        // Movement Spec is selected (the bucket is effectively constant per spec, so there's no
        // point asking the user to enter it — see MovementSearchCriteria.fileSourceQuery — and
        // Properties keys come directly from that spec's own parser config), so both are hidden
        // until a spec is chosen. Only rebuilt when the selected Movement Spec changes (not on
        // every keystroke into a value) — same reactive-narrowing reasoning as RuleWidget's
        // operandField.
        child <-- searchVar.signal.map(_.movementSpecId).distinct.map { specId =>
          if specId.isEmpty then div()
          else
            val keys = RuleWidget.columnsFor(specId, state.now().movementSpecs, state.now().fileFeeds)
            div(
              cls := "mt-2",
              div(
                cls := "grid grid-cols-1 md:grid-cols-3 gap-4",
                Form.field(
                  "searchFileSourceFilename",
                  "Filename",
                  searchVar.now().fileSourceFilename,
                  v => searchVar.update(_.copy(fileSourceFilename = v))
                )
              ),
              if keys.isEmpty then div()
              else
                div(
                  cls := "mt-2",
                  h4(cls := Form.sectionLabelCls + " mb-2", "Properties"),
                  div(
                    cls := "grid grid-cols-1 md:grid-cols-3 gap-4",
                    keys.map { key =>
                      Form.field(
                        s"prop_$key",
                        key,
                        searchVar.now().properties.getOrElse(key, ""),
                        v =>
                          searchVar.update(s =>
                            s.copy(properties = if v.isEmpty then s.properties - key else s.properties.updated(key, v))
                          )
                      )
                    }
                  )
                )
            )
        },
        div(
          cls := "grid grid-cols-1 md:grid-cols-3 gap-4 mt-2",
          Form.selectField(
            "searchReconciliation",
            "Reconciliation",
            Seq(
              ReconciliationFilterPayload.All.toString          -> "Any",
              ReconciliationFilterPayload.Unreconciled.toString -> "Unreconciled",
              ReconciliationFilterPayload.OpenBalance.toString  -> "Open balance (unreconciled + in progress)"
            ),
            searchVar.now().reconciliation.toString,
            v => searchVar.update(_.copy(reconciliation = ReconciliationFilterPayload.valueOf(v)))
          )
        ),
        div(
          cls := "mt-4",
          Buttons.linkButton("Search", Observer[Unit](_ => searchBus.emit(())))
        )
      ),
      child.maybe <-- searchVar.signal.map(
        _.searchError.map(err => div(cls := "text-sm text-red-600 dark:text-red-400 mb-4", err))
      ),
      child <-- searchVar.signal.map(_.results).map {
        case None => div()
        case Some(results) =>
          div(
            if results.size == 1000 then
              div(
                cls := s"${Form.mutedTextCls} text-xs mb-2",
                "Showing the first 1000 results — narrow your filters to see more precisely."
              )
            else div(),
            Table.create(
              Seq(
                "Amount",
                "Primary Account",
                "Secondary Account",
                "Movement Spec",
                "Reference",
                "Type",
                "Reconciled",
                "Created At"
              ),
              results.map { r =>
                Seq(
                  span(r.amount.toString),
                  span(r.inAccountName),
                  span(r.outAccountName),
                  span(r.movementSpecName),
                  a(
                    href := s"/components/movements/${r.id}",
                    cls  := "text-md-on-surface dark:text-gray-200 hover:underline cursor-pointer",
                    onClick --> { e =>
                      e.preventDefault()
                      Nav.toMovementDetail(r.id)
                    },
                    r.referenceId
                  ),
                  div(
                    span(MovementLabels.typeLabel(r.movementType, r.adjustmentType)),
                    r.adjustmentNote
                      .map(n => div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", n))
                      .getOrElse(emptyNode)
                  ),
                  Pill(
                    if r.reconciled then "Yes" else "No",
                    if r.reconciled then Pill.Tone.Good else Pill.Tone.Neutral
                  ),
                  span(ViewHelper.formatDateTime(r.createdAt))
                )
              },
              numCols = Set(0)
            )
          )
      }
    )

  private def parseDateStart(dateStr: String): Option[java.time.OffsetDateTime] =
    if dateStr.isEmpty then None
    else
      scala.util
        .Try(java.time.LocalDate.parse(dateStr).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime)
        .toOption

  // Exclusive upper bound (the day after the chosen "To" date) so the whole "To" day is included
  // via `createdAt < createdTo` on the backend, rather than needing a `<=` against a bare date.
  private def parseDateEndExclusive(dateStr: String): Option[java.time.OffsetDateTime] =
    if dateStr.isEmpty then None
    else
      scala.util
        .Try(java.time.LocalDate.parse(dateStr).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime)
        .toOption

  private def renderDiagram(
    s:                 State,
    ff:                FundFlowPayload,
    configureReconBus: EventBus[AccountPayload],
    manualMatchBus:    EventBus[AccountPayload],
    deleteSpecBus:     EventBus[MovementSpecPayload],
    state:             Var[State]
  ): HtmlElement =
    val posOf   = computePositions(s.accounts, s.movementSpecs)
    val svgHtml = buildSvg(s.accounts, s.movementSpecs, posOf)

    val accountOverlays = s.accounts.flatMap { acc =>
      acc.id.flatMap { id =>
        posOf.get(id).map { (x, y) =>
          div(
            styleAttr := s"position:absolute;left:${x}px;top:${y}px;width:${boxW}px;height:${boxH}px;cursor:pointer;z-index:20",
            onClick --> (_ => state.update(_.copy(selectedAccount = Some(acc), selectedMovementSpec = None)))
          )
        }
      }
    }

    val arrowOverlays = s.movementSpecs.flatMap { ms =>
      for {
        (x1, y1) <- posOf.get(ms.outAccountId)
        (x2, y2) <- posOf.get(ms.inAccountId)
      } yield {
        val midX = (x1 + boxW + x2) / 2
        val midY = (y1 + boxH / 2 + y2 + boxH / 2) / 2
        div(
          styleAttr := s"position:absolute;left:${midX - 11}px;top:${midY - 11}px;width:22px;height:22px;cursor:pointer;z-index:20;display:flex;align-items:center;justify-content:center;background:white;border:1px solid #d1d5db;border-radius:50%;font-size:11px;color:#6b7280",
          onClick --> (_ => state.update(_.copy(selectedMovementSpec = Some(ms), selectedAccount = None))),
          "✎"
        )
      }
    }

    val menuOpen = s.selectedAccount.isDefined || s.selectedMovementSpec.isDefined
    val backdrop =
      if menuOpen then
        div(
          styleAttr := "position:fixed;inset:0;z-index:10",
          onClick --> (_ => state.update(_.copy(selectedAccount = None, selectedMovementSpec = None)))
        )
      else div()

    val accountMenu = s.selectedAccount.flatMap { selected =>
      selected.id.flatMap { id =>
        posOf.get(id).map { (x, y) =>
          renderAccountMenu(s, selected, ff, x, y + boxH + 4, configureReconBus, manualMatchBus, deleteSpecBus, state)
        }
      }
    }.getOrElse(div())

    val movementSpecMenu = s.selectedMovementSpec.flatMap { selected =>
      for {
        (x1, y1) <- posOf.get(selected.outAccountId)
        (x2, y2) <- posOf.get(selected.inAccountId)
      } yield {
        val midX = (x1 + boxW + x2) / 2
        val midY = (y1 + boxH / 2 + y2 + boxH / 2) / 2
        renderMovementSpecMenu(selected, ff, midX, midY + 16, deleteSpecBus, state)
      }
    }.getOrElse(div())

    val svgEl = div(
      onMountCallback { ctx =>
        ctx.thisNode.ref.innerHTML = svgHtml
      }
    )
    val allChildren = Seq(svgEl, backdrop) ++ accountOverlays ++ arrowOverlays :+ accountMenu :+ movementSpecMenu
    div(
      div(styleAttr := "position:relative;display:inline-block").amend(allChildren*),
      diagramLegend
    )

  private def renderAccountMenu(
    s:                 State,
    selected:          AccountPayload,
    ff:                FundFlowPayload,
    x:                 Int,
    y:                 Int,
    configureReconBus: EventBus[AccountPayload],
    manualMatchBus:    EventBus[AccountPayload],
    deleteSpecBus:     EventBus[MovementSpecPayload],
    state:             Var[State]
  ): HtmlElement =
    val editBtn = button(
      cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
      onClick --> { _ =>
        state.update(_.copy(selectedAccount = None, editingAccountId = Some(selected.id.getOrElse("new"))))
      },
      "Edit"
    )

    val reconBtn =
      if selected.accountType == AccountTypePayload.Recon then
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick --> { _ =>
            state.update(_.copy(selectedAccount = None))
            configureReconBus.emit(selected)
          },
          "Configure Reconciliation"
        )
      else div()

    val manualMatchBtn =
      if selected.accountType == AccountTypePayload.Recon then
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick --> { _ =>
            state.update(_.copy(selectedAccount = None))
            manualMatchBus.emit(selected)
          },
          "Manual Match"
        )
      else div()

    val selectedId = selected.id.getOrElse("")
    val linkedIds = s.movementSpecs.flatMap { ms =>
      if ms.outAccountId == selectedId then Some(ms.inAccountId)
      else if ms.inAccountId == selectedId then Some(ms.outAccountId)
      else None
    }.toSet

    // A link is only ever valid between one Recon account and one Primary-or-Secondary account —
    // same rule the backend enforces (FundFlowOperations.validateAccountLink) — so an incompatible
    // target never even shows up as an option here.
    val linkButtons = s.accounts
      .filter(a =>
        a.id != selected.id
          && !a.id.exists(linkedIds.contains)
          && (a.accountType == AccountTypePayload.Recon) != (selected.accountType == AccountTypePayload.Recon)
      )
      .map { target =>
        val nonRecon            = if selected.accountType == AccountTypePayload.Recon then target else selected
        val recon               = if selected.accountType == AccountTypePayload.Recon then selected else target
        val (outAccId, inAccId) = ViewHelper.accountLinkFields(nonRecon, recon)
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick --> { _ =>
            state.update(_.copy(selectedAccount = None, editingMovementSpec = Some((outAccId, inAccId, "new"))))
          },
          s"Link to ${target.name}"
        )
      }

    val unlinkButtons = s.movementSpecs
      .filter(ms => ms.outAccountId == selectedId || ms.inAccountId == selectedId)
      .flatMap { ms =>
        val otherId = if ms.outAccountId == selectedId then ms.inAccountId else ms.outAccountId
        s.accounts.find(_.id.contains(otherId)).map { other =>
          button(
            cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-red-600",
            onClick --> { _ =>
              state.update(_.copy(selectedAccount = None))
              deleteSpecBus.emit(ms)
            },
            s"Unlink from ${other.name}"
          )
        }
      }

    div(
      styleAttr := s"position:absolute;left:${x}px;top:${y}px;z-index:30;background:white;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 4px 6px rgba(0,0,0,0.1);min-width:160px;overflow:hidden"
    ).amend((editBtn +: reconBtn +: manualMatchBtn +: linkButtons ++: unlinkButtons)*)

  private def renderMovementSpecMenu(
    spec:          MovementSpecPayload,
    ff:            FundFlowPayload,
    x:             Int,
    y:             Int,
    deleteSpecBus: EventBus[MovementSpecPayload],
    state:         Var[State]
  ): HtmlElement =
    div(
      styleAttr := s"position:absolute;left:${x}px;top:${y}px;z-index:30;background:white;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 4px 6px rgba(0,0,0,0.1);min-width:160px;overflow:hidden",
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
        onClick --> { _ =>
          state.update(
            _.copy(
              selectedMovementSpec = None,
              editingMovementSpec  = Some((spec.outAccountId, spec.inAccountId, spec.id.getOrElse("new")))
            )
          )
        },
        "Edit Relationship"
      ),
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
        onClick --> { _ =>
          state.update(_.copy(selectedMovementSpec = None))
          Nav.toTestParser(spec.fileFeedId)
        },
        "Test Parser"
      ),
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-red-600",
        onClick --> { _ =>
          state.update(_.copy(selectedMovementSpec = None))
          deleteSpecBus.emit(spec)
        },
        "Delete"
      )
    )

  private def buildSvg(
    accounts:      Seq[AccountPayload],
    movementSpecs: Seq[MovementSpecPayload],
    posOf:         Map[String, (Int, Int)]
  ): String =
    val svgW = posOf.values.map(_._1).maxOption.fold(margin * 2)(_ + boxW + margin)
    val svgH = posOf.values.map(_._2).maxOption.fold(margin * 2)(_ + boxH + margin)
    val sb   = new StringBuilder
    sb.append(s"""<svg width="$svgW" height="$svgH" xmlns="http://www.w3.org/2000/svg">""")
    sb.append(
      """<defs><marker id="arrowhead" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto"><polygon points="0 0, 10 3.5, 0 7" fill="#6b7280" /></marker></defs>"""
    )
    movementSpecs.foreach { ms =>
      val (fromId, toId) = pointTowardRecon(accounts, ms)
      for { fromPos <- posOf.get(fromId); toPos <- posOf.get(toId) } {
        val (x1, y1, x2, y2) = edgePoints(fromPos, toPos)
        val midX             = (x1 + x2) / 2
        val midY             = (y1 + y2) / 2
        sb.append(
          s"""<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="#6b7280" stroke-width="2" marker-end="url(#arrowhead)" />"""
        )
        if ms.name.nonEmpty then
          sb.append(
            s"""<text x="$midX" y="${midY - 14}" text-anchor="middle" dominant-baseline="middle" fill="#6b7280" font-size="11" font-family="sans-serif">${xmlEscape(
                ms.name
              )}</text>"""
          )
      }
    }
    accounts.foreach { acc =>
      acc.id.foreach { id =>
        posOf.get(id).foreach { (x, y) =>
          val (fill, stroke, textFill) =
            acc.accountType match
              case AccountTypePayload.Recon =>
                ("var(--md-primary-container)", "var(--md-primary)", "var(--md-on-primary-container)")
              case AccountTypePayload.Primary =>
                (
                  "var(--acct-role-primary-container)",
                  "var(--acct-role-primary)",
                  "var(--acct-role-primary-on-container)"
                )
              case AccountTypePayload.Secondary =>
                (
                  "var(--acct-role-secondary-container)",
                  "var(--acct-role-secondary)",
                  "var(--acct-role-secondary-on-container)"
                )
          sb.append(
            s"""<rect x="$x" y="$y" width="$boxW" height="$boxH" rx="8" fill="$fill" stroke="$stroke" stroke-width="2" />"""
          )
          sb.append(
            s"""<text x="${x + boxW / 2}" y="${y + boxH / 2}" text-anchor="middle" dominant-baseline="middle" fill="$textFill" font-size="13" font-family="sans-serif">${xmlEscape(
                acc.name
              )}</text>"""
          )
        }
      }
    }
    sb.append("</svg>")
    sb.toString

  // Connects the two facing edges of two boxes, regardless of which one is left/right of the
  // other — needed because arrows no longer follow a fixed in/out column ordering (see
  // pointTowardRecon below), so the "from" box can be on either side of the "to" box.
  private def edgePoints(from: (Int, Int), to: (Int, Int)): (Int, Int, Int, Int) =
    val (xA, yA) = from
    val (xB, yB) = to
    if xA <= xB then (xA + boxW, yA + boxH / 2, xB, yB + boxH / 2)
    else (xA, yA + boxH / 2, xB + boxW, yB + boxH / 2)

  // The underlying movement direction (out -> in) doesn't match how customers think about a fund
  // flow: they think of every other account as feeding into (or out of) the Recon account, not in
  // terms of which side happens to be labeled "in". So arrows are drawn to always point *at*
  // whichever end is Recon, regardless of whether Recon is that spec's in or out account. Falls
  // back to the original out -> in direction if neither (or both) side is Recon.
  private def pointTowardRecon(accounts: Seq[AccountPayload], ms: MovementSpecPayload): (String, String) =
    val reconIds = accounts.filter(_.accountType == AccountTypePayload.Recon).flatMap(_.id).toSet
    if reconIds.contains(ms.outAccountId) && !reconIds.contains(ms.inAccountId) then (ms.inAccountId, ms.outAccountId)
    else if reconIds.contains(ms.inAccountId) && !reconIds.contains(ms.outAccountId) then
      (ms.outAccountId, ms.inAccountId)
    else (ms.outAccountId, ms.inAccountId)

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def diagramLegend: HtmlElement =
    def swatch(colorVar: String, label: String): HtmlElement =
      div(
        cls := "flex items-center gap-1.5",
        div(styleAttr := s"width:12px;height:12px;border-radius:3px;background:var($colorVar)"),
        span(cls      := "text-xs text-md-on-surface-variant dark:text-gray-400", label)
      )
    div(
      cls := "flex items-center gap-4 mt-3",
      swatch("--md-primary", "Recon Account"),
      swatch("--acct-role-primary", "Primary Account"),
      swatch("--acct-role-secondary", "Secondary Account")
    )

  private def computePositions(
    accounts:      Seq[AccountPayload],
    movementSpecs: Seq[MovementSpecPayload]
  ): Map[String, (Int, Int)] =
    val columnOf = mutable.Map[String, Int]()
    val allInIds = movementSpecs.map(_.inAccountId).toSet
    accounts.foreach(a => a.id.foreach(id => if !allInIds.contains(id) then columnOf(id) = 0))
    var changed = true
    while changed do
      changed = false
      movementSpecs.foreach { ms =>
        val newInCol = columnOf.getOrElse(ms.outAccountId, 0) + 1
        if newInCol > columnOf.getOrElse(ms.inAccountId, -1) then
          columnOf(ms.inAccountId) = newInCol
          changed                  = true
      }
    accounts.foreach(a => a.id.foreach(id => if !columnOf.contains(id) then columnOf(id) = 0))
    val byColumn = accounts.groupBy(a => columnOf.getOrElse(a.id.getOrElse(""), 0))
    val posOf    = mutable.Map[String, (Int, Int)]()
    byColumn.foreach { (col, accs) =>
      accs.sortBy(_.name).zipWithIndex.foreach { (acc, row) =>
        acc.id.foreach(id => posOf(id) = (margin + col * colStride, margin + row * rowStride))
      }
    }
    posOf.toMap

  case class SearchState(
    accountId:          String                                   = "",
    amount:             String                                   = "",
    movementSpecId:     String                                   = "",
    properties:         Map[String, String]                      = Map.empty,
    fileSourceFilename: String                                   = "",
    reconciliation:     ReconciliationFilterPayload              = ReconciliationFilterPayload.All,
    createdFrom:        String                                   = "",
    createdTo:          String                                   = "",
    movementType:       String                                   = "",
    adjustmentType:     String                                   = "",
    results:            Option[Seq[MovementSearchResultPayload]] = None,
    searchError:        Option[String]                           = None
  )

  enum ReconcileStatus:
    case Idle, InProgress, Success
    case Failed(msg: String)

  case class State(
    accounts:             Seq[AccountPayload]                    = Seq.empty,
    movementSpecs:        Seq[MovementSpecPayload]               = Seq.empty,
    fileFeeds:            Seq[FileFeedPayload]                   = Seq.empty,
    selectedAccount:      Option[AccountPayload]                 = None,
    selectedMovementSpec: Option[MovementSpecPayload]            = None,
    reconcileStatuses:    Map[String, ReconcileStatus]           = Map.empty,
    summaries:            Map[String, AccountSummaryViewPayload] = Map.empty,
    // Set by the Overview tab's Unreconciled tile, consumed once by searchMovementsTab to seed and auto-run a pre-filled search — see that
    // function. (accountId, reconciliation filter)
    pendingSearch: Option[(String, ReconciliationFilterPayload)] = None,
    // Drive the account/movement-spec edit forms as an in-page Modal instead of a route navigation
    // (Nav.toMaintainAccount/toMaintainMovementSpec), so the diagram stays mounted underneath
    // rather than disappearing while the form is open. None = no modal open.
    editingAccountId:    Option[String]                   = None,
    editingMovementSpec: Option[(String, String, String)] = None, // (outAccountId, inAccountId, id)
    editingReconSpec:    Option[(String, String)]         = None, // (recon accountId, spec id or "new")
    ingesting:           Boolean                          = false
  )
