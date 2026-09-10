package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.*
import upickle.default.*

import scala.collection.mutable

object FundFlowPage:

  private val activeTab = Var("Account")

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
    val ffId              = ff.id.getOrElse("")
    val ffVar             = Var(ff)
    val state             = Var(State())
    val configureReconBus = EventBus[AccountPayload]()
    val manualMatchBus    = EventBus[AccountPayload]()
    val deleteSpecBus     = EventBus[MovementSpecPayload]()
    val reconcileBus      = EventBus[String]()
    val saveFundFlowBus   = EventBus[Unit]()
    // Fired when the account/movement-spec modal closes, to pick up whatever it just created or
    // edited — re-fetches both lists since either could have changed either way.
    val refreshBus = EventBus[Unit]()
    val closeModal = () => {
      state.update(_.copy(editingAccountId = None, editingMovementSpec = None))
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
          val counts = summary.accounts.flatMap(a => a.account.id.map(_ -> a.unreconciledCount)).toMap
          state.update(_.copy(unreconciledCounts = counts))
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
          Nav.toMaintainReconSpec(account.id.get, reconSpecId)
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
            case None               => Nav.toMaintainReconSpec(account.id.get, "new")
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
      reconcileBus.events.map { id =>
        state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.InProgress)))
        id
      }.flatMapSwitch { id =>
        ApiClient
          .postEmpty[ujson.Value](HttpPath.url(s"${HttpPath.account}/$id/reconcile"), SessionStore.token)
          .map(res => (id, res))
      } --> {
        case (id, Right(_)) =>
          state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Success)))
        case (id, Left(e)) =>
          state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Failed(e))))
      },
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.toFundFlows())),
        PageHeader.title(child.text <-- ffVar.signal.map(_.name))
      ),
      div(
        cls := "w-full border-b border-gray-200 dark:border-gray-700 mb-6",
        div(
          cls := "w-full px-8 flex -mb-px space-x-8",
          tabButton("Accounts", "Account", activeTab),
          tabButton("Search Movements", "Search", activeTab),
          tabButton("Configuration", "Configuration", activeTab)
        )
      ),
      div(
        cls := "w-full px-8",
        child <-- activeTab.signal.map {
          case "Configuration" =>
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
          case "Account" =>
            div(
              child <-- state.signal.map { s =>
                Table.create(
                  Seq(
                    "Name",
                    "Account Type",
                    "Cash Balance",
                    "Unreconciled Balance",
                    "Unreconciled Count",
                    "",
                    ""
                  ),
                  s.accounts.sortBy(_.name).map { account =>
                    val accountId    = account.id.getOrElse("")
                    val status       = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
                    val unreconCount = s.unreconciledCounts.getOrElse(accountId, 0)
                    // Both cells jump to the Search Movements tab (not the old per-account
                    // Unreconciled Movements page) with the account and "unreconciled only"
                    // pre-filled and the search already run — see searchMovementsTab's
                    // pendingSearchAccountId handling.
                    val goToUnreconciledSearch = Observer[Unit](_ =>
                      state.update(_.copy(pendingSearchAccountId = Some(accountId)))
                      activeTab.set("Search")
                    )
                    val isRecon = account.accountType == "Recon"
                    val unreconCell =
                      if isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick.mapToUnit --> goToUnreconciledSearch,
                          unreconCount.toString
                        )
                      else span(cls := "text-right w-full inline-block", unreconCount.toString)
                    val unreconBalanceCell =
                      if isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick.mapToUnit --> goToUnreconciledSearch,
                          f"$$${account.unreconciledBalance}%.2f"
                        )
                      else span()
                    val reconciliationsCell =
                      if isRecon then
                        a(
                          href := s"/components/accounts/$accountId/recent-reconciliations",
                          cls  := "text-md-primary hover:underline cursor-pointer",
                          onClick --> { e =>
                            e.preventDefault()
                            Nav.toAccountReconciliations(accountId)
                          },
                          "Reconciliations"
                        )
                      else span()
                    // Jumps straight to Manual Match for this account's recon spec (fetched on
                    // click, since a row doesn't otherwise know its spec id) — this table is the
                    // default landing tab, so it's the entry point people actually find, unlike the
                    // Configuration tab's account-diagram menu where this same shortcut also lives.
                    val manualMatchCell =
                      if isRecon then
                        button(
                          cls := "text-md-primary hover:underline cursor-pointer",
                          onClick --> (_ => manualMatchBus.emit(account)),
                          "Manual Match"
                        )
                      else span()
                    val typePill = account.accountType match
                      case "Recon"     => Pill("Recon", Pill.Tone.Accent)
                      case "Primary"   => Pill("Primary", Pill.Tone.Good)
                      case "Secondary" => Pill("Secondary", Pill.Tone.Neutral)
                      case other       => Pill(other, Pill.Tone.Neutral)
                    Seq(
                      span(cls := "inline-block w-40", account.name),
                      typePill,
                      span(cls := "text-right w-full inline-block", f"$$${account.balance}%.2f"),
                      unreconBalanceCell,
                      unreconCell,
                      reconciliationsCell,
                      manualMatchCell
                    )
                  },
                  numCols = Set(2, 3, 4)
                )
              }
            )
          case "Search" => searchMovementsTab(ffId, state)
          case _        => div()
        }
      )
    )

  private def tabButton(label: String, tabId: String, activeTab: Var[String]): HtmlElement =
    button(
      cls <-- activeTab.signal.map { active =>
        val base = "px-4 pb-3 text-sm font-medium border-b-2 transition-colors"
        if active == tabId then s"$base border-gray-900 text-gray-900 dark:border-white dark:text-white"
        else
          s"$base border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-200 dark:hover:border-gray-500"
      },
      onClick --> (_ => activeTab.set(tabId)),
      label
    )

  // Kept local to this tab (rebuilt fresh whenever the user switches back to it) rather than
  // folded into the page-level State — none of the other tabs need this data, and it keeps the
  // shared State from growing ten fields that only ever matter here. The one exception is
  // pendingSearchAccountId: the Accounts tab's "Unreconciled Count"/"Unreconciled Balance" links
  // switch to this tab and stash the account there so it can be consumed once, right here, to
  // seed a pre-filled, auto-run search — that hand-off has to go through the shared State since
  // this tab's own searchVar doesn't exist yet at the moment those links are clicked.
  private def searchMovementsTab(ffId: String, state: Var[State]): HtmlElement =
    val prefillAccountId = state.now().pendingSearchAccountId
    if prefillAccountId.isDefined then state.update(_.copy(pendingSearchAccountId = None))
    val searchVar = Var(
      SearchState(accountId = prefillAccountId.getOrElse(""), unreconciledOnly = prefillAccountId.isDefined)
    )
    val searchBus = EventBus[Unit]()

    val autoSearchTrigger =
      if prefillAccountId.isDefined then EventStream.fromValue(()) else EventStream.empty

    div(
      EventStream.merge(autoSearchTrigger, searchBus.events).flatMapSwitch { _ =>
        val s = searchVar.now()
        val req = MovementSearchRequest(
          fundFlowId       = ffId,
          accountId        = Option(s.accountId).filter(_.nonEmpty),
          amount           = scala.util.Try(BigDecimal(s.amount)).toOption,
          movementSpecId   = Option(s.movementSpecId).filter(_.nonEmpty),
          properties       = s.properties.filter(_._2.nonEmpty),
          fileSourceQuery  = Option(s.fileSourceFilename).filter(_.nonEmpty),
          unreconciledOnly = s.unreconciledOnly,
          createdFrom      = parseDateStart(s.createdFrom),
          createdTo        = parseDateEndExclusive(s.createdTo),
          movementType     = Option(s.movementType).filter(_.nonEmpty)
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
              "ManualAdjustment" -> "Manual Adjustment",
              "Reversal"         -> "Reversal"
            ),
            searchVar.now().movementType,
            v => searchVar.update(_.copy(movementType = v))
          ),
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
        label(
          cls := "flex items-center gap-2 text-sm text-md-on-surface dark:text-gray-200 cursor-pointer mt-2",
          input(
            typ := "checkbox",
            checked <-- searchVar.signal.map(_.unreconciledOnly),
            onClick --> (_ => searchVar.update(s => s.copy(unreconciledOnly = !s.unreconciledOnly)))
          ),
          "Unreconciled only"
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
                  span(r.movementType.getOrElse("—")),
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
      if selected.accountType == "Recon" then
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
      if selected.accountType == "Recon" then
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
          && (a.accountType == "Recon") != (selected.accountType == "Recon")
      )
      .map { target =>
        val nonRecon            = if selected.accountType == "Recon" then target else selected
        val recon               = if selected.accountType == "Recon" then selected else target
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
              case "Recon" => ("var(--md-primary-container)", "var(--md-primary)", "var(--md-on-primary-container)")
              case "Primary" =>
                (
                  "var(--acct-role-primary-container)",
                  "var(--acct-role-primary)",
                  "var(--acct-role-primary-on-container)"
                )
              case "Secondary" =>
                (
                  "var(--acct-role-secondary-container)",
                  "var(--acct-role-secondary)",
                  "var(--acct-role-secondary-on-container)"
                )
              case _ => ("white", "#d1d5db", "#374151")
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
    val reconIds = accounts.filter(_.accountType == "Recon").flatMap(_.id).toSet
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
    unreconciledOnly:   Boolean                                  = false,
    createdFrom:        String                                   = "",
    createdTo:          String                                   = "",
    movementType:       String                                   = "",
    results:            Option[Seq[MovementSearchResultPayload]] = None,
    searchError:        Option[String]                           = None
  )

  enum ReconcileStatus:
    case Idle, InProgress, Success
    case Failed(msg: String)

  case class State(
    accounts:             Seq[AccountPayload]          = Seq.empty,
    movementSpecs:        Seq[MovementSpecPayload]     = Seq.empty,
    fileFeeds:            Seq[FileFeedPayload]         = Seq.empty,
    selectedAccount:      Option[AccountPayload]       = None,
    selectedMovementSpec: Option[MovementSpecPayload]  = None,
    reconcileStatuses:    Map[String, ReconcileStatus] = Map.empty,
    unreconciledCounts:   Map[String, Int]             = Map.empty,
    // Set by the Accounts tab's "Unreconciled Count"/"Unreconciled Balance" links, consumed once
    // by searchMovementsTab to seed and auto-run a pre-filled search — see that function.
    pendingSearchAccountId: Option[String] = None,
    // Drive the account/movement-spec edit forms as an in-page Modal instead of a route navigation
    // (Nav.toMaintainAccount/toMaintainMovementSpec), so the diagram stays mounted underneath
    // rather than disappearing while the form is open. None = no modal open.
    editingAccountId:    Option[String]                   = None,
    editingMovementSpec: Option[(String, String, String)] = None // (outAccountId, inAccountId, id)
  )
