package perfectpair.page

sealed trait Page:
  val path: String

object Page:
  case object Login                 extends Page { val path = "/login" }
  case object Home                  extends Page { val path = "/" }
  case object FundFlows             extends Page { val path = "/components/fund-flows" }
  case object FundFlow              extends Page { val path = "/components/fund-flow" }
  case object MaintainFundFlow      extends Page { val path = "/components/maintain-fund-flow" }
  case object Accounts              extends Page { val path = "/components/accounts" }
  case object MaintainAccount       extends Page { val path = "/components/maintain-account" }
  case object MaintainMovementSpec  extends Page { val path = "/components/maintain-movement-spec" }
  case object ReconSpecs            extends Page { val path = "/components/recon-specs" }
  case object MaintainReconSpec     extends Page { val path = "/components/maintain-recon-spec" }
  case object Ingest                extends Page { val path = "/components/ingest" }
  case object Jobs                  extends Page { val path = "/components/jobs" }
  case object UnreconciledMovements extends Page { val path = "/components/unreconciled-movements" }
  case object Reconciliations       extends Page { val path = "/components/reconciliations" }
