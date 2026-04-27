package perfectpair.route

import perfectpair.page.*

import scala.util.Try

object Route {
  object Home:
    def unapply(path: String): Option[Unit] = if path == "/" then Some(()) else None

  object ComponentDemo:
    def unapply(path: String): Option[Page] = Try {
      path.split("/components/")(1) match {
        case "toggle"                 => Page.Toggle
        case "fund-flows"             => Page.FundFlows
        case "maintain-fund-flow"     => Page.MaintainFundFlow
        case "accounts"               => Page.Accounts
        case "maintain-account"       => Page.MaintainAccount
        case "movement-specs"         => Page.MovementSpecs
        case "maintain-movement-spec" => Page.MaintainMovementSpec
        case "recon-specs"            => Page.ReconSpecs
        case "maintain-recon-spec"    => Page.MaintainReconSpec
        case "ingest"                 => Page.Ingest
        case "jobs"                   => Page.Jobs
      }
    }.toOption

  object Login:
    def unapply(path: String): Option[Unit] = if path.startsWith("login") then Some(()) else None

}
