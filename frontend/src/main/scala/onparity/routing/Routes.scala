package onparity.routing

import com.raquo.laminar.api.L.*
import frontroute.*
import onparity.view.pages.*

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
          path("accounts" / segment / "maintain-recon-spec" / segment)((accountId, reconSpecId) =>
            MaintainReconSpecPage(accountId, reconSpecId)
          ),
          path("accounts" / segment / "ingest" / segment)((accountId, reconSpecId) =>
            IngestPage(accountId, reconSpecId)
          ),
          path("accounts" / segment / "reconciliations" / segment)((accountId, reconSpecId) =>
            ReconciliationsPage(accountId, reconSpecId)
          ),
          path("reconciliations" / segment)(id => ReconciliationDetailPage(id)),
          path("movements" / segment)(id => MovementDetailPage(id)),
          path("accounts" / segment / "manual-match" / segment)((accountId, reconSpecId) =>
            ManualMatchPage(accountId, reconSpecId)
          ),
          path("accounts" / segment / "unreconciled-movements")(accountId => UnreconciledMovementsPage(accountId)),
          path("accounts" / segment / "recent-reconciliations")(accountId => AccountReconciliationsPage(accountId)),
          path("maintain-movement-spec" / segment / segment / segment)((outAccountId, inAccountId, id) =>
            MaintainMovementSpecPage(outAccountId, inAccountId, id)
          ),
          path("jobs")(JobsPage()),
          path("events")(EventsPage()),
          path("file-inventory")(FileInventoryPage()),
          path("exceptions")(ExceptionsPage()),
          path("exceptions" / segment)(id => ExceptionDetailPage(id)),
          path("coming-soon" / "recon-rules")(ComingSoonPage("Recon Rules")),
          path("reports")(ReportsPage()),
          path("coming-soon" / "users")(ComingSoonPage("Users & Roles")),
          path("coming-soon" / "settings")(ComingSoonPage("Settings"))
        )
      },
      noneMatched(runEffect(BrowserNavigation.pushState(url = "/login")))
    )
