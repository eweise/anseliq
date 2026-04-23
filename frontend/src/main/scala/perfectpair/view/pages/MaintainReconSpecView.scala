package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction}
import perfectpair.view.components.{Buttons, Form}
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MaintainReconSpecView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(reconSpec = Some(spec)), Cmd.None)
      case Msg.ReconSpecAction(spec, Create) =>
        (model, HttpHelper.createReconSpec(spec))
      case Msg.ReconSpecAction(spec, Created) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(spec, Update) =>
        (model, HttpHelper.updateReconSpec(spec))
      case Msg.ReconSpecAction(spec, Updated) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(_, Delete) =>
        val id = model.reconSpec.flatMap(_.id).getOrElse("")
        (model, HttpHelper.deleteReconSpec(id))
      case Msg.ReconSpecAction(_, Deleted) =>
        (model, Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))

  private def movementSpecSelect(
    fieldID:    String,
    labelName:  String,
    selectedId: String,
    specs:      Seq[MovementSpecPayload],
    onSelect:   String => AppMsg
  ): Html[AppMsg] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(labelName)),
      select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onSelect)
      )(
        specs.map(spec =>
          option(
            value := spec.id.getOrElse(""),
            if spec.id.getOrElse("") == selectedId then attribute("selected", "selected") else EmptyAttribute
          )(text(spec.name))
        )*
      )
    )

  def apply(model: Model): Html[AppMsg] =
    val firstSpecId = model.movementSpecs.headOption.flatMap(_.id).getOrElse("")
    val spec = model.reconSpec.getOrElse(
      ReconciliationSpecPayload(
        accountId         = model.account.id,
        inMovementSpecId  = firstSpecId,
        outMovementSpecId = firstSpecId
      )
    )
    val action = if spec.id.isEmpty then Create else Update
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Recon Spec")),
        Form.field[AppMsg](
          fieldID      = "description",
          labelName    = "Description",
          defaultValue = Some(spec.description),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(description = value))))
        ),
        Form.field[AppMsg](
          fieldID      = "agingLimit",
          labelName    = "Aging Limit",
          defaultValue = Some(spec.agingLimit.toString),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(agingLimit = value.toIntOption.getOrElse(0)))))
        ),
        Form.field[AppMsg](
          fieldID      = "relationship",
          labelName    = "Relationship",
          defaultValue = Some(spec.relationship),
          onChangFn    = Some(value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(relationship = value))))
        ),
        movementSpecSelect(
          fieldID    = "inMovementSpecId",
          labelName  = "In Movement Spec",
          selectedId = spec.inMovementSpecId,
          specs      = model.movementSpecs,
          onSelect   = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(inMovementSpecId = value)))
        ),
        movementSpecSelect(
          fieldID    = "outMovementSpecId",
          labelName  = "Out Movement Spec",
          selectedId = spec.outMovementSpecId,
          specs      = model.movementSpecs,
          onSelect   = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(outMovementSpecId = value)))
        ),
        div(cls := "flex justify-between mt-6")(
          if spec.id.isDefined then
            Buttons.textButton("Delete", AppMsg.MaintainReconSpecMsg(Msg.ReconSpecAction(spec, Delete)))
          else
            div()(),
          div(cls := "flex gap-2")(
            Buttons.textButton("Cancel", AppMsg.NavigateToReconSpecs(model.account)),
            Buttons.linkButton("Save", AppMsg.MaintainReconSpecMsg(Msg.ReconSpecAction(spec, action)))
          )
        )
      )
    )

  enum Msg:
    case FormUpdate(spec: ReconciliationSpecPayload)
    case ReconSpecAction(spec: ReconciliationSpecPayload, action: PayloadAction)

  case class Model(
    account:       AccountPayload                    = AccountPayload(),
    movementSpecs: Seq[MovementSpecPayload]          = Seq(),
    reconSpec:     Option[ReconciliationSpecPayload] = None
  )
