package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object UnreconciledMovementsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.MovementsFromBackend(movements) =>
        (model.copy(movements = movements), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    val accountNameById = model.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(s"${model.movementSpec.name} — Unreconciled Movements")
        ),
        Buttons.linkButton("Back to Movement Specs", AppMsg.NavigateToMovementSpecs(model.fundFlow)),
        Table.create(
          Seq("Reference ID", "Amount", "In Account", "Out Account"),
          model.movements.map(m =>
            Seq(
              text(m.referenceId),
              text(m.amount.toString),
              text(accountNameById.getOrElse(m.inAccountId, m.inAccountId)),
              text(accountNameById.getOrElse(m.outAccountId, m.outAccountId))
            )
          )
        )
      )
    )

  enum Msg:
    case MovementsFromBackend(movements: Seq[MovementPayload])

  case class Model(
    fundFlow:     FundFlowPayload      = FundFlowPayload(),
    movementSpec: MovementSpecPayload  = MovementSpecPayload(),
    accounts:     Seq[AccountPayload]  = Seq(),
    movements:    Seq[MovementPayload] = Seq()
  )
