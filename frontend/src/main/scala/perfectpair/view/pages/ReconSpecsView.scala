package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object ReconSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.ReconSpecsFromBackend(specs) =>
        (model.copy(reconSpecs = specs), Cmd.None)
      case Msg.MovementSpecsFromBackend(specs) =>
        (model.copy(movementSpecs = specs), Cmd.None)
      case Msg.SelectReconSpec(reconSpec) =>
        (model, Cmd.emit(AppMsg.NavigateToMaintainReconSpec(model.account, model.movementSpecs, reconSpec)))

  def apply(model: Model): Html[AppMsg] =
    val specNameById = model.movementSpecs.flatMap(ms => ms.id.map(_ -> ms.name)).toMap
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Recon Spec", AppMsg.ReconSpecsMsg(Msg.SelectReconSpec(None))),
        Table.create(
          Seq("Description", "Aging Limit", "Relationship", "In Movement Spec", "Out Movement Spec", ""),
          model.reconSpecs
            .sortBy(_.description)
            .map(spec =>
              Seq(
                text(spec.description),
                text(spec.agingLimit.toString),
                text(spec.relationship),
                text(specNameById.getOrElse(spec.inMovementSpecId, spec.inMovementSpecId)),
                text(specNameById.getOrElse(spec.outMovementSpecId, spec.outMovementSpecId)),
                Buttons.textButton("Edit", AppMsg.ReconSpecsMsg(Msg.SelectReconSpec(Some(spec))))
              )
            )
        )
      )
    )

  enum Msg:
    case ReconSpecsFromBackend(specs: Seq[ReconciliationSpecPayload])
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case SelectReconSpec(reconSpec: Option[ReconciliationSpecPayload])

  case class Model(
    account:       AccountPayload                 = AccountPayload(),
    reconSpecs:    Seq[ReconciliationSpecPayload] = Seq(),
    movementSpecs: Seq[MovementSpecPayload]       = Seq()
  )
