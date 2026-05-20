package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

import scala.collection.mutable

object FundFlowView:

  private val boxW      = 140
  private val boxH      = 52
  private val colGap    = 100
  private val rowGap    = 30
  private val margin    = 30
  private val colStride = boxW + colGap
  private val rowStride = boxH + rowGap

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    try
      msg match
        case Msg.AccountsFromBackend(accounts)   => (model.copy(accounts = accounts), Cmd.None)
        case Msg.MovementSpecsFromBackend(specs) => (model.copy(movementSpecs = specs), Cmd.None)
        case Msg.SelectAccount(account) =>
          (model.copy(selectedAccount = Some(account), selectedMovementSpec = None), Cmd.None)
        case Msg.SelectMovementSpec(spec) =>
          (model.copy(selectedMovementSpec = Some(spec), selectedAccount = None), Cmd.None)
        case Msg.DismissMenu => (model.copy(selectedAccount = None, selectedMovementSpec = None), Cmd.None)
        case Msg.EditAccount(account) =>
          (
            model.copy(selectedAccount = None),
            Cmd.Emit(AppMsg.NavigateToMaintainAccount(model.fundFlow, Some(account)))
          )
        case Msg.EditRelationship(spec) =>
          (
            model.copy(selectedMovementSpec = None),
            Cmd.Emit(
              AppMsg.NavigateToMaintainMovementSpec(
                model.fundFlow,
                model.accounts,
                Some(spec),
                AppMsg.NavigateToFundFlow(model.fundFlow)
              )
            )
          )
    catch
      case ex: Exception =>
        println(ex)
        (model, Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(text(model.fundFlow.name))
      ),
      if model.accounts.isEmpty then div(cls := "text-gray-500 text-sm")(text("No accounts"))
      else renderDiagramWithOverlay(model, computePositions(model.accounts, model.movementSpecs))
    )

  private def renderDiagramWithOverlay(model: Model, posOf: Map[String, (Int, Int)]): Html[AppMsg] =
    val accountOverlays: Seq[Html[AppMsg]] = model.accounts.flatMap { acc =>
      acc.id.flatMap { id =>
        posOf.get(id).map { (x, y) =>
          div(
            style := s"position:absolute;left:${x}px;top:${y}px;width:${boxW}px;height:${boxH}px;cursor:pointer;z-index:20",
            onClick(AppMsg.FundFlowViewMsg(Msg.SelectAccount(acc)))
          )()
        }
      }
    }

    val arrowOverlays: Seq[Html[AppMsg]] = model.movementSpecs.flatMap { ms =>
      for {
        (x1, y1) <- posOf.get(ms.outAccountId)
        (x2, y2) <- posOf.get(ms.inAccountId)
      } yield {
        val midX    = (x1 + boxW + x2) / 2
        val midY    = (y1 + boxH / 2 + y2 + boxH / 2) / 2
        val btnSize = 22
        div(
          style := s"position:absolute;left:${midX - btnSize / 2}px;top:${midY - btnSize / 2}px;width:${btnSize}px;height:${btnSize}px;cursor:pointer;z-index:20;display:flex;align-items:center;justify-content:center;background:white;border:1px solid #d1d5db;border-radius:50%;font-size:11px;color:#6b7280",
          onClick(AppMsg.FundFlowViewMsg(Msg.SelectMovementSpec(ms)))
        )(text("✎"))
      }
    }

    val accountMenu: Html[AppMsg] = model.selectedAccount.flatMap { selected =>
      selected.id.flatMap { id =>
        posOf.get(id).map { (x, y) =>
          renderAccountMenu(model.accounts, selected, model.movementSpecs, model.fundFlow, x, y + boxH + 4)
        }
      }
    }.getOrElse(div()())

    val movementSpecMenu: Html[AppMsg] = model.selectedMovementSpec.flatMap { selected =>
      for {
        (x1, y1) <- posOf.get(selected.outAccountId)
        (x2, y2) <- posOf.get(selected.inAccountId)
      } yield {
        val midX = (x1 + boxW + x2) / 2
        val midY = (y1 + boxH / 2 + y2 + boxH / 2) / 2
        renderMovementSpecMenu(selected, midX, midY + 16)
      }
    }.getOrElse(div()())

    val menuOpen = model.selectedAccount.isDefined || model.selectedMovementSpec.isDefined
    val backdrop: Html[AppMsg] =
      if menuOpen then
        div(style := "position:fixed;inset:0;z-index:10", onClick(AppMsg.FundFlowViewMsg(Msg.DismissMenu)))()
      else div()()

    val children: Seq[Html[AppMsg]] =
      Seq(Html.raw("div")(buildSvg(model.accounts, model.movementSpecs, posOf)), backdrop) ++
        accountOverlays ++ arrowOverlays :+ accountMenu :+ movementSpecMenu

    div(style := "position:relative;display:inline-block")(children*)

  private def renderAccountMenu(
    accounts:        Seq[AccountPayload],
    selectedAccount: AccountPayload,
    movementSpecs:   Seq[MovementSpecPayload],
    fundFlow:        FundFlowPayload,
    x:               Int,
    y:               Int
  ): Html[AppMsg] =
    var buttons = List(
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
        onClick(AppMsg.FundFlowViewMsg(Msg.EditAccount(selectedAccount)))
      )(text("Edit")),
      if (selectedAccount.isRecon) then
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick(
            AppMsg
              .NavigateToMaintainReconSpec(selectedAccount, movementSpecs, None, AppMsg.NavigateToFundFlow(fundFlow))
          )
        )(text("Configure Reconciliation"))
      else div()
    )

    val addMovementSpecButtons = accounts
      .filter(_.id != selectedAccount.id)
      .filter { targetAccount =>
        !movementSpecs.exists(s =>
          (s.inAccountId == targetAccount.id.get && s.outAccountId == selectedAccount.id.get) ||
            (s.inAccountId == selectedAccount.id.get && s.outAccountId == targetAccount.id.get)
        )
        true
      }
      .map(a =>
        button(
          cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
          onClick(
            AppMsg
              .NavigateToMaintainMovementSpec(fundFlow, Seq(a, selectedAccount), None, AppMsg.NavigateToFundFlow(fundFlow))
          )
        )(text(s"Link to ${a.name}"))
      )

    buttons = buttons ++ addMovementSpecButtons
    div(
      style := s"position:absolute;left:${x}px;top:${y}px;z-index:30;background:white;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 4px 6px rgba(0,0,0,0.1);min-width:160px;overflow:hidden"
    )(buttons*)

  private def renderMovementSpecMenu(spec: MovementSpecPayload, x: Int, y: Int): Html[AppMsg] =
    div(
      style := s"position:absolute;left:${x}px;top:${y}px;z-index:30;background:white;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 4px 6px rgba(0,0,0,0.1);min-width:160px;overflow:hidden"
    )(
      button(
        cls := "block w-full text-left px-4 py-2 hover:bg-gray-100 text-sm text-gray-700",
        onClick(AppMsg.FundFlowViewMsg(Msg.EditRelationship(spec)))
      )(text("Edit Relationship"))
    )

  private def buildSvg(
    accounts:      Seq[AccountPayload],
    movementSpecs: Seq[MovementSpecPayload],
    posOf:         Map[String, (Int, Int)]
  ): String = {
    val svgW = posOf.values.map(_._1).maxOption.fold(margin * 2)(_ + boxW + margin)
    val svgH = posOf.values.map(_._2).maxOption.fold(margin * 2)(_ + boxH + margin)

    val sb = new StringBuilder
    sb.append(s"""<svg width="$svgW" height="$svgH" xmlns="http://www.w3.org/2000/svg">""")
    sb.append(
      """<defs><marker id="arrowhead" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto">""" +
        """<polygon points="0 0, 10 3.5, 0 7" fill="#6b7280" /></marker></defs>"""
    )

    movementSpecs.foreach { ms =>
      for {
        (x1, y1) <- posOf.get(ms.outAccountId)
        (x2, y2) <- posOf.get(ms.inAccountId)
      } sb.append(
        s"""<line x1="${x1 + boxW}" y1="${y1 + boxH / 2}" x2="$x2" y2="${y2 + boxH / 2}" """ +
          """stroke="#6b7280" stroke-width="2" marker-end="url(#arrowhead)" />"""
      )
    }

    accounts.foreach { acc =>
      acc.id.foreach { id =>
        posOf.get(id).foreach { (x, y) =>
          val fill   = if (acc.isRecon) "#f3f0ff" else "white"
          val stroke = if (acc.isRecon) "#7c3aed" else "#d1d5db"
          sb.append(
            s"""<rect x="$x" y="$y" width="$boxW" height="$boxH" rx="8" fill="$fill" stroke="$stroke" stroke-width="2" />"""
          )
          sb.append(
            s"""<text x="${x + boxW / 2}" y="${y + boxH / 2}" text-anchor="middle" dominant-baseline="middle" """ +
              s"""fill="#374151" font-size="13" font-family="sans-serif">${xmlEscape(acc.name)}</text>"""
          )
        }
      }
    }

    sb.append("</svg>")
    sb.toString
  }

  private def xmlEscape(str: String): String =
    str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def computePositions(
    accounts:      Seq[AccountPayload],
    movementSpecs: Seq[MovementSpecPayload]
  ): Map[String, (Int, Int)] = {
    val columnOf = mutable.Map[String, Int]()
    val allInIds = movementSpecs.map(_.inAccountId).toSet
    accounts.foreach { a =>
      a.id.foreach(id => if (!allInIds.contains(id)) columnOf(id) = 0)
    }

    var changed = true
    while (changed) {
      changed = false
      movementSpecs.foreach { ms =>
        val newInCol = columnOf.getOrElse(ms.outAccountId, 0) + 1
        if (newInCol > columnOf.getOrElse(ms.inAccountId, -1)) {
          columnOf(ms.inAccountId) = newInCol
          changed                  = true
        }
      }
    }

    accounts.foreach { a =>
      a.id.foreach(id => if (!columnOf.contains(id)) columnOf(id) = 0)
    }

    val byColumn = accounts.groupBy(a => columnOf.getOrElse(a.id.getOrElse(""), 0))
    val posOf    = mutable.Map[String, (Int, Int)]()
    byColumn.foreach { (col, accs) =>
      accs.sortBy(_.name).zipWithIndex.foreach { (acc, row) =>
        acc.id.foreach { id =>
          posOf(id) = (margin + col * colStride, margin + row * rowStride)
        }
      }
    }
    posOf.toMap
  }

  enum Msg:
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case SelectAccount(account: AccountPayload)
    case SelectMovementSpec(spec: MovementSpecPayload)
    case DismissMenu
    case EditAccount(account: AccountPayload)
    case EditRelationship(spec: MovementSpecPayload)

  case class Model(
    fundFlow:             FundFlowPayload             = FundFlowPayload(),
    accounts:             Seq[AccountPayload]         = Seq(),
    movementSpecs:        Seq[MovementSpecPayload]    = Seq(),
    selectedAccount:      Option[AccountPayload]      = None,
    selectedMovementSpec: Option[MovementSpecPayload] = None
  )
