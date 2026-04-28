package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, ReconciliationPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object ReconciliationsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.ReconciliationsFromBackend(reconciliations) =>
        (model.copy(reconciliations = reconciliations), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(s"${model.reconSpec.description} — Reconciliations")
        ),
        Buttons.linkButton("Back to Recon Specs", AppMsg.NavigateToReconSpecs(model.account)),
        Table.create(
          Seq("Description", "Status", "Applied Rules", "Created At", "Updated At"),
          model.reconciliations.map(r =>
            Seq(
              text(r.description),
              text(r.status),
              text(r.appliedRules),
              text(ViewHelper.formatDateTime(r.createdAt)),
              text(ViewHelper.formatDateTime(r.updatedAt))
            )
          )
        )
      )
    )

  enum Msg:
    case ReconciliationsFromBackend(reconciliations: Seq[ReconciliationPayload])

  case class Model(
    account:         AccountPayload             = AccountPayload(),
    reconSpec:       ReconciliationSpecPayload  = ReconciliationSpecPayload(),
    reconciliations: Seq[ReconciliationPayload] = Seq()
  )
