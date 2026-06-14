package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{DisplayName, FundFlowPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Table}
import upickle.default.*

object FundFlowsPage:

  def apply(): HtmlElement =
    val fundFlows = Var(Seq.empty[FundFlowPayload])

    div(
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.url(HttpPath.fundFlows), SessionStore.token) --> {
        case Right(ffs) =>
          fundFlows.set(ffs)
          AppState.fundFlows.set(ffs)
        case Left(err) => AppState.globalError.set(Some(err))
      },
      cls := "flex flex-col mb-8 pt-20",
      div(
        cls := "w-full px-8",
        div(
          cls := "flex justify-between items-center my-8",
          h2(cls := "text-xl font-medium", "Fund Flows"),
          Buttons.linkButton(
            "Create",
            Observer[Unit](_ =>
              AppState.activeFundFlow.set(None)
              Nav.toMaintainFundFlow()
            )
          )
        ),
        child <-- fundFlows.signal.map { ffs =>
          Table.create(
            Seq("Name", "Created At", "Updated At", "", ""),
            ffs.sortBy(_.name).map { ff =>
              Seq(
                span(ff.name),
                span(ff.createdAt.map(ViewHelper.formatDateTime).getOrElse("")),
                span(ff.updatedAt.map(ViewHelper.formatDateTime).getOrElse("")),
                Buttons.textButton(DisplayName.accounts, Observer[Unit](_ => Nav.toAccounts(ff))),
                Buttons.textButton(
                  "Edit",
                  Observer[Unit](_ =>
                    AppState.activeFundFlow.set(Some(ff))
                    Nav.toMaintainFundFlow()
                  )
                )
              )
            }
          )
        }
      )
    )
