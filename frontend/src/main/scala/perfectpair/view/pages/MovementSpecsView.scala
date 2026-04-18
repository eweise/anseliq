package perfectpair.view.pages

import perfectpair.common.model.*
import perfectpair.model.Msg as AppMsg
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MovementSpecsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.MovementSpecsFromBackend(specs) =>
        (
          model.copy(movementSpecs = specs),
          HttpHelper.fetchAccountsForMovementSpecs(model.fundFlow.id.get) |+|
            PrettyLogger.info("fetching accounts for movement specs")
        )
      case Msg.AccountsFromBackend(accounts) =>
        val combined = zipAccountsAndMovementSpecs(accounts, model.movementSpecs)
        (
          model.copy(accounts = accounts, movementSpecAndAccounts = combined),
          PrettyLogger.info("zipped accounts and movement specs")
        )
      case Msg.SelectMovementSpec(msa) =>
        val spec = msa.map(_.spec)
        (model, Cmd.emit(AppMsg.NavigateToMaintainMovementSpec(model.fundFlow, model.accounts, spec)))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Movement Spec", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(None))),
        Table.create(
          Seq("Name", "In Account", "Out Account", ""),
          model.movementSpecAndAccounts
            .sortBy(_.spec.name)
            .map(msa =>
              Seq(
                text(msa.spec.name),
                text(msa.inAccount.name),
                text(msa.outAccount.name),
                Buttons.textButton("Edit", AppMsg.MovementSpecsMsg(Msg.SelectMovementSpec(Some(msa))))
              )
            )
        )
      )
    )

  enum Msg:
    case MovementSpecsFromBackend(specs: Seq[MovementSpecPayload])
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case SelectMovementSpec(msa: Option[MovementSpecAndAccounts])

  case class Model(
    fundFlow:                FundFlowPayload              = FundFlowPayload(),
    accounts:                Seq[AccountPayload]          = Seq(),
    movementSpecs:           Seq[MovementSpecPayload]     = Seq(),
    movementSpecAndAccounts: Seq[MovementSpecAndAccounts] = Seq()
  )
