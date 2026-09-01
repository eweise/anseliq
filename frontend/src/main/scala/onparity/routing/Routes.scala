package onparity.routing

import com.raquo.laminar.api.L.*
import frontroute.*
import onparity.state.SessionStore
import onparity.view.pages.*

object Routes:

  def apply(): HtmlElement =
    div(
      styleAttr := "display: contents",
      initRouting
    ).amend(
      // Unconditional redirect, no session check needed here: /components/dashboard already has
      // its own whenFalse guard below, which bounces to /login if there's no session — reusing it
      // rather than duplicating the same check at the root.
      pathEnd(runEffect(BrowserNavigation.pushState(url = "/components/dashboard"))),
      path("login")(LoginPage()),
      // No session (missing/cleared token) -> bounce to /login instead of rendering the page.
      // This only catches the case of navigating straight to a /components/... URL with no
      // session; an expired/invalid token discovered mid-session is handled separately, by
      // ApiClient reacting to a 401 on whatever request actually surfaces it.
      //
      // whenFalse(...), not a plain `if`: a host-language `if` here would only ever run once,
      // when this route tree is first built at app mount (before any login has happened) — not
      // re-checked on each subsequent SPA navigation. whenFalse's condition is by-name and lives
      // inside the closure frontroute re-invokes on every navigation, so SessionStore.current is
      // read fresh each time a nav link is clicked.
      pathPrefix("components") {
        firstMatch(
          whenFalse(SessionStore.current.now().isEmpty) {
            firstMatch(
              path("dashboard")(DashboardPage()),
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
              path("file-feeds" / segment / "test-parse")(id => TestParserPage(id)),
              path("accounts" / segment / "manual-match" / segment)((accountId, reconSpecId) =>
                ManualMatchPage(accountId, reconSpecId)
              ),
              path("unreconciled-movements")(AllUnreconciledMovementsPage()),
              path("accounts" / segment / "recent-reconciliations")(accountId => AccountReconciliationsPage(accountId)),
              path("maintain-movement-spec" / segment / segment / segment)((outAccountId, inAccountId, id) =>
                MaintainMovementSpecPage(outAccountId, inAccountId, id)
              ),
              path("maintain-file-feed" / segment)(id => MaintainFileFeedPage(id)),
              path("file-feeds")(FileFeedsPage()),
              path("jobs")(JobsPage()),
              path("events")(EventsPage()),
              path("file-inventory")(FileInventoryPage()),
              path("exceptions")(ExceptionsPage()),
              path("exceptions" / segment)(id => ExceptionDetailPage(id)),
              path("coming-soon" / "recon-rules")(ComingSoonPage("Recon Rules")),
              path("reports")(ReportsPage()),
              path("users")(UsersPage()),
              path("maintain-user" / segment)(id => MaintainUserPage(id)),
              path("fix-bad-file")(FixBadFilePage()),
              path("coming-soon" / "settings")(ComingSoonPage("Settings"))
            )
          },
          runEffect(BrowserNavigation.pushState(url = "/login"))
        )
      },
      noneMatched(runEffect(BrowserNavigation.pushState(url = "/login")))
    )
