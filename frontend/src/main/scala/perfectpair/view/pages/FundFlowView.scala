package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import tyrian.Html
import tyrian.Html.*
import tyrian.Cmd
import zio.Task

import scala.collection.mutable

object FundFlowView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.AccountsFromBackend(accounts) =>
        (model.copy(accounts = accounts), Cmd.None)
      case Msg.MovementSpecsFromBackend(specs) =>
        (model.copy(movementSpecs = specs), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(text(model.fundFlow.name))
      ),
      renderDiagram(model.accounts, model.movementSpecs)
    )

  private def renderDiagram(accounts: Seq[AccountPayload], movementSpecs: Seq[MovementSpecPayload]): Html[AppMsg] =
    if accounts.isEmpty then div(cls := "text-gray-500 text-sm")(text("No accounts"))
    else Html.raw("div")(buildSvg(accounts, movementSpecs))

  private def buildSvg(accounts: Seq[AccountPayload], movementSpecs: Seq[MovementSpecPayload]): String = {
    val boxW      = 140
    val boxH      = 52
    val colGap    = 100
    val rowGap    = 30
    val margin    = 30
    val colStride = boxW + colGap
    val rowStride = boxH + rowGap

    val columnOf  = mutable.Map[String, Int]()
    val allOutIds = movementSpecs.map(_.outAccountId).toSet
    accounts.foreach { a =>
      a.id.foreach { id => if !allOutIds.contains(id) then columnOf(id) = 0 }
    }

    var changed = true
    while (changed) {
      changed = false
      movementSpecs.foreach { ms =>
        val newOutCol = columnOf.getOrElse(ms.inAccountId, 0) + 1
        if (newOutCol > columnOf.getOrElse(ms.outAccountId, -1)) {
          columnOf(ms.outAccountId) = newOutCol
          changed = true
        }
      }
    }

    accounts.foreach { a =>
      a.id.foreach { id => if (!columnOf.contains(id)) columnOf(id) = 0 }
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

    val numCols = if (columnOf.isEmpty) 1 else columnOf.values.max + 1
    val maxRows = if (byColumn.isEmpty) 1 else byColumn.values.map(_.size).max
    val svgW    = margin * 2 + numCols * colStride
    val svgH    = margin * 2 + maxRows * rowStride

    val sb = new StringBuilder
    sb.append(s"""<svg width="$svgW" height="$svgH" xmlns="http://www.w3.org/2000/svg">""")
    sb.append(
      """<defs><marker id="arrowhead" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto">""" +
        """<polygon points="0 0, 10 3.5, 0 7" fill="#6b7280" /></marker></defs>"""
    )

    movementSpecs.foreach { ms =>
      for {
        (x1, y1) <- posOf.get(ms.inAccountId)
        (x2, y2) <- posOf.get(ms.outAccountId)
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
          val textX  = x + boxW / 2
          val textY  = y + boxH / 2
          sb.append(s"""<rect x="$x" y="$y" width="$boxW" height="$boxH" rx="8" fill="$fill" stroke="$stroke" stroke-width="2" />""")
          sb.append(
            s"""<text x="$textX" y="$textY" text-anchor="middle" dominant-baseline="middle" """ +
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

  enum Msg:
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])

  case class Model(
    fundFlow:      FundFlowPayload          = FundFlowPayload(),
    accounts:      Seq[AccountPayload]      = Seq(),
    movementSpecs: Seq[MovementSpecPayload] = Seq()
  )
