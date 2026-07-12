package perfectpair.routing

import com.raquo.laminar.api.L.*
import frontroute.*
import perfectpair.view.pages.*

object Routes:

  def apply(): HtmlElement =
    div(
      styleAttr := "display: contents",
      initRouting
    ).amend(
      pathEnd(WelcomePage()),
      path("login")(LoginPage()),
      pathPrefix("components") {
        firstMatch(
          path("fund-flows")(FundFlowsPage()),
          path("fund-flow")(FundFlowPage()),
          path("maintain-fund-flow")(MaintainFundFlowPage()),
          path("accounts" / segment / "maintain")(accountId => MaintainAccountPage(accountId)),
          path("accounts" / segment / "recon-specs")(accountId => ReconSpecsPage(accountId)),
          path("accounts" / segment / "maintain-recon-spec")(accountId => MaintainReconSpecPage(accountId)),
          path("accounts" / segment / "ingest")(accountId => IngestPage(accountId)),
          path("accounts" / segment / "reconciliations")(accountId => ReconciliationsPage(accountId)),
          path("accounts" / segment / "manual-match")(accountId => ManualMatchPage(accountId)),
          path("accounts" / segment / "unreconciled-movements")(accountId => UnreconciledMovementsPage(accountId)),
          path("accounts")(AccountsPage()),
          path("maintain-movement-spec")(MaintainMovementSpecPage()),
          path("jobs")(JobsPage()),
          path("events")(EventsPage())
        )
      },
      noneMatched(runEffect(BrowserNavigation.pushState(url = "/login")))
    )
