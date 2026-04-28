package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction, PrettyLogger}
import perfectpair.view.components.{Buttons, DataSourceConfigWidget, Form}
import tyrian.Html.*
import tyrian.{Cmd, EmptyAttribute, Html}
import zio.Task

object MaintainMovementSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("movement spec form modified"))
      case Msg.DataSourceMsg(widgetMsg) =>
        (model.copy(dataSourceWidget = DataSourceConfigWidget.update(model.dataSourceWidget, widgetMsg)), Cmd.None)
      case Msg.MovementSpecAction(spec, Create) =>
        val toSave = spec.copy(dataSourceConfig = DataSourceConfigWidget.toDataSourceConfig(model.dataSourceWidget))
        (model, HttpHelper.createMovementSpec(toSave) |+| PrettyLogger.info("creating movement spec"))
      case Msg.MovementSpecAction(spec, Created) =>
        (
          model.copy(movementSpec = Some(spec)),
          PrettyLogger.info("created movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow))
        )
      case Msg.MovementSpecAction(spec, Update) =>
        val toSave = spec.copy(dataSourceConfig = DataSourceConfigWidget.toDataSourceConfig(model.dataSourceWidget))
        (model, HttpHelper.updateMovementSpec(toSave) |+| PrettyLogger.info("updating movement spec"))
      case Msg.MovementSpecAction(spec, Updated) =>
        (
          model.copy(movementSpec = Some(spec)),
          PrettyLogger.info("updated movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow))
        )

  def apply(model: Model): Html[AppMsg] =
    val firstAccountId = model.accounts.headOption.flatMap(_.id).getOrElse("")
    val spec =
      model.movementSpec.getOrElse(MovementSpecPayload(inAccountId = firstAccountId, outAccountId = firstAccountId))
    val action = if spec.id.isEmpty then Create else Update
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Movement Spec")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(spec.name),
          onChangFn    = Some(value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(name = value))))
        ),
        accountSelect(
          fieldID    = "inAccountId",
          labelName  = "In Account",
          selectedId = spec.inAccountId,
          accounts   = model.accounts,
          onSelect   = value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(inAccountId = value)))
        ),
        accountSelect(
          fieldID    = "outAccountId",
          labelName  = "Out Account",
          selectedId = spec.outAccountId,
          accounts   = model.accounts,
          onSelect   = value => AppMsg.MaintainMovementSpecsMsg(Msg.FormUpdate(spec.copy(outAccountId = value)))
        ),
        DataSourceConfigWidget(model.dataSourceWidget, msg => AppMsg.MaintainMovementSpecsMsg(Msg.DataSourceMsg(msg))),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateToMovementSpecs(model.fundFlow)),
          Buttons.linkButton("Save", AppMsg.MaintainMovementSpecsMsg(Msg.MovementSpecAction(spec, action)))
        )
      )
    )

  private def accountSelect(
    fieldID:    String,
    labelName:  String,
    selectedId: String,
    accounts:   Seq[AccountPayload],
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
        accounts.map(account =>
          option(
            value := account.id.getOrElse(""),
            if account.id.getOrElse("") == selectedId then attribute("selected", "selected") else EmptyAttribute
          )(text(account.name))
        )*
      )
    )

  enum Msg:
    case FormUpdate(spec: MovementSpecPayload)
    case MovementSpecAction(spec: MovementSpecPayload, action: PayloadAction)
    case DataSourceMsg(msg: DataSourceConfigWidget.Msg)

  case class Model(
    fundFlow:         FundFlowPayload              = FundFlowPayload(),
    accounts:         Seq[AccountPayload]          = Seq(),
    movementSpec:     Option[MovementSpecPayload]  = None,
    dataSourceWidget: DataSourceConfigWidget.Model = DataSourceConfigWidget.Model()
  )
