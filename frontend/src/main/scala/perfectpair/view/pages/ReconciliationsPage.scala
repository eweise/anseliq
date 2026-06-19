package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{ReconciliationPayload, ReconciliationSpecPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Table}
import upickle.default.*

object ReconciliationsPage:

  def apply(accountId: String): HtmlElement =
    val spec   = AppState.editReconSpec.now().getOrElse(ReconciliationSpecPayload())
    val specId = spec.id.getOrElse("")
    val recons = Var(Seq.empty[ReconciliationPayload])

    div(
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.reconSpec}/$specId/reconciliations"),
        SessionStore.token
      ) --> {
        case Right(rs) => recons.set(rs)
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        h2(cls := "text-2xl font-medium mb-6", "Reconciliations"),
        Buttons.linkButton("Back to Recon Specs", Observer[Unit](_ => Nav.toReconSpecs(accountId))),
        child <-- recons.signal.map { rs =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Created At", "Updated At"),
            rs.map(r =>
              Seq(
                span(r.description),
                span(r.status),
                span(r.appliedRules),
                span(ViewHelper.formatDateTime(r.createdAt)),
                span(ViewHelper.formatDateTime(r.updatedAt))
              )
            )
          )
        }
      )
    )
