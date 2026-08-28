package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Form, PageHeader, Pill, Table}
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
    val deleteSpecBus     = EventBus[MovementSpecPayload]()
    val reconcileBus      = EventBus[String]()
    val saveFundFlowBus   = EventBus[Unit]()

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
                cls := "border border-md-outline dark:border-gray-700 rounded-lg p-6 max-w-md",
                h3(cls := "font-medium mb-4 text-md-on-surface dark:text-white", "Fund Flow Settings"),
                Form.field("ffName", "Name", ff.name, v => ffVar.update(_.copy(name = v))),
                Form.numberField(
                  "ffDailyCutoffHours",
                  "Cutoff Hour (0-23)",
                  ff.dailyCutoffHours,
                  0,
                  23,
                  v => ffVar.update(_.copy(dailyCutoffHours = v))
                ),
                Buttons.linkButton("Save Fund Flow", Observer[Unit](_ => saveFundFlowBus.emit(())))
              ),
              div(
                cls := "border border-md-outline dark:border-gray-700 rounded-lg p-6",
                div(
                  cls := "flex items-center justify-between mb-4",
                  h3(cls := "font-medium text-md-on-surface dark:text-white", "Accounts"),
                  Buttons.linkButton(
                    "New Account",
                    Observer[Unit](_ =>
                      state.update(_.copy(selectedAccount = None, selectedMovementSpec = None))
                      Nav.toMaintainAccount(None)
                    )
                  )
                ),
                child <-- state.signal.map { s =>
                  if s.accounts.isEmpty then div(cls := "text-gray-500 dark:text-gray-400 text-sm", "No accounts")
                  else renderDiagram(s, ff, configureReconBus, deleteSpecBus, state)
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
                    ""
                  ),
                  s.accounts.sortBy(_.name).map { account =>
                    val accountId    = account.id.getOrElse("")
                    val status       = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
                    val unreconCount = s.unreconciledCounts.getOrElse(accountId, 0)
                    val unreconCell =
                      if account.isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick --> (_ => Nav.toUnreconciledMovements(accountId)),
                          unreconCount.toString
                        )
                      else span(cls := "text-right w-full inline-block", unreconCount.toString)
                    val unreconBalanceCell =
                      if account.isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick --> (_ => Nav.toUnreconciledMovements(accountId)),
                          f"$$${account.unreconciledBalance}%.2f"
                        )
                      else span()
                    val reconciliationsCell =
                      if account.isRecon then
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
                    Seq(
                      span(cls := "inline-block w-40", account.name),
                      if account.isRecon then Pill("Recon", Pill.Tone.Accent) else Pill("Source", Pill.Tone.Neutral),
                      span(cls := "text-right w-full inline-block", f"$$${account.balance}%.2f"),
                      unreconBalanceCell,
                      unreconCell,
                      reconciliationsCell
                    )
                  },
                  numCols = Set(2, 3, 4)
                )
              }
            )
          case _ => div()
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

  private def renderDiagram(
    s:                 State,
    ff:                FundFlowPayload,
    configureReconBus: EventBus[AccountPayload],
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
          renderAccountMenu(s, selected, ff, x, y + boxH + 4, configureReconBus, deleteSpecBus, state)
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
    div(styleAttr := "position:relative;display:inline-block").amend(allChildren*)

  private def renderAccountMenu(
    s:                 State,
    selected:          AccountPayload,
    ff:                FundFlowPayload,
    x:                 Int,
    y:                 Int,
    configureReconBus: EventBus[AccountPayload],
    deleteSpecBus:     EventBus[MovementSpecPayload],
    state:             Var[State]
  ): HtmlElement =
    val editBtn = button(
      cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
      onClick --> { _ =>
        state.update(_.copy(selectedAccount = None))
        Nav.toMaintainAccount(selected.id)
      },
      "Edit"
    )

    val reconBtn =
      if selected.isRecon then
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick --> { _ =>
            state.update(_.copy(selectedAccount = None))
            configureReconBus.emit(selected)
          },
          "Configure Reconciliation"
        )
      else div()

    val selectedId = selected.id.getOrElse("")
    val linkedIds = s.movementSpecs.flatMap { ms =>
      if ms.outAccountId == selectedId then Some(ms.inAccountId)
      else if ms.inAccountId == selectedId then Some(ms.outAccountId)
      else None
    }.toSet

    val linkButtons = s.accounts
      .filter(a => a.id != selected.id && !a.id.exists(linkedIds.contains))
      .map { target =>
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick --> { _ =>
            state.update(_.copy(selectedAccount = None))
            Nav.toMaintainMovementSpec(selected.id.getOrElse(""), target.id.getOrElse(""))
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
    ).amend((editBtn +: reconBtn +: linkButtons ++: unlinkButtons)*)

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
          state.update(_.copy(selectedMovementSpec = None))
          Nav.toMaintainMovementSpec(spec.outAccountId, spec.inAccountId, spec.id.getOrElse("new"))
        },
        "Edit Relationship"
      ),
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
        onClick --> { _ =>
          state.update(_.copy(selectedMovementSpec = None))
          Nav.toTestParser(spec.id.getOrElse(""))
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
      for { (x1, y1) <- posOf.get(ms.outAccountId); (x2, y2) <- posOf.get(ms.inAccountId) } {
        val midX = (x1 + boxW + x2) / 2
        val midY = (y1 + boxH / 2 + y2 + boxH / 2) / 2
        sb.append(
          s"""<line x1="${x1 + boxW}" y1="${y1 + boxH / 2}" x2="$x2" y2="${y2 + boxH / 2}" stroke="#6b7280" stroke-width="2" marker-end="url(#arrowhead)" />"""
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
          val fill   = if acc.isRecon then "var(--md-primary-container)" else "white"
          val stroke = if acc.isRecon then "var(--md-primary)" else "#d1d5db"
          sb.append(
            s"""<rect x="$x" y="$y" width="$boxW" height="$boxH" rx="8" fill="$fill" stroke="$stroke" stroke-width="2" />"""
          )
          sb.append(
            s"""<text x="${x + boxW / 2}" y="${y + boxH / 2}" text-anchor="middle" dominant-baseline="middle" fill="#374151" font-size="13" font-family="sans-serif">${xmlEscape(
                acc.name
              )}</text>"""
          )
        }
      }
    }
    sb.append("</svg>")
    sb.toString

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

  enum ReconcileStatus:
    case Idle, InProgress, Success
    case Failed(msg: String)

  case class State(
    accounts:             Seq[AccountPayload]          = Seq.empty,
    movementSpecs:        Seq[MovementSpecPayload]     = Seq.empty,
    selectedAccount:      Option[AccountPayload]       = None,
    selectedMovementSpec: Option[MovementSpecPayload]  = None,
    reconcileStatuses:    Map[String, ReconcileStatus] = Map.empty,
    unreconciledCounts:   Map[String, Int]             = Map.empty
  )
