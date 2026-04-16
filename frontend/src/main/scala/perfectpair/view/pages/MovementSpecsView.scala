package perfectpair.view.pages

import perfectpair.common.model.{FundFlowPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PrettyLogger
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MovementSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.MovementSpecsFromBackend(specs) =>
        (model.copy(movementSpecs = specs), PrettyLogger.info("updated model with movement specs"))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Table.create(
          Seq("ID", "Name", "In Account", "Out Account"),
          model.movementSpecs
            .sortBy(_.name)
            .map(spec =>
              Seq(
                text(spec.id.getOrElse("")),
                text(spec.name),
                text(spec.inAccountId),
                text(spec.outAccountId)
              )
            )
        )
      )
    )

  enum Msg:
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])

  case class Model(
    fundFlow:      FundFlowPayload          = FundFlowPayload(),
    movementSpecs: Seq[MovementSpecPayload] = Seq()
  )
