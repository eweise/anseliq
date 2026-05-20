package perfectpair.page

import perfectpair.model.*
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.pages.*
import tyrian.{Cmd, Html}
import zio.*

sealed trait Page {
  val path:   String
  val render: Model => Html[Msg]
  val isSecured: Boolean = false

  def doNavigate(model: Model): Cmd[Task, Msg] =
    beforeEnter(model) |+| PrettyLogger.info("Navigating To Page") |+| Cmd.emit(Msg.DoNavigate(this))

  protected def beforeEnter(model: Model): Cmd[Task, Msg] = Cmd.None

  def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg])
}

object Page {
  object Login extends Page {
    val path:   String             = "/login"
    val render: Model => Html[Msg] = m => LoginPage(m.loginModel)
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.LoginMsg(sub) =>
        val (sub2, cmd) = LoginPage.update(model.loginModel, sub)
        (model.copy(loginModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object Home extends Page {
    val path:   String             = "/"
    val render: Model => Html[Msg] = m => Welcome(m.homeModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.HomeMsg(sub) =>
        val (sub2, cmd) = Welcome.update(model.homeModel, sub)
        (model.copy(homeModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] = HttpHelper.fetchServerMessage
  }

  object FundFlows extends Page {
    val path:   String             = "/components/fund-flows"
    val render: Model => Html[Msg] = m => FundFlowsView(m.fundFlowsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.FundFlowsMsg(sub) =>
        val (sub2, cmd) = FundFlowsView.update(model.fundFlowsModel, sub)
        (model.copy(fundFlowsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Fund Flows page") |+| HttpHelper.fetchFundFlows
  }

  object MaintainFundFlow extends Page {
    val path:   String             = "/components/maintain-fund-flow"
    val render: Model => Html[Msg] = m => MaintainFundFlowView(m.maintainFundFlowModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainFundFlowMsg(sub) =>
        val (sub2, cmd) = MaintainFundFlowView.update(model.maintainFundFlowModel, sub)
        (model.copy(maintainFundFlowModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Fund Flow page")
  }

  object Accounts extends Page {
    val path:   String             = "/components/accounts"
    val render: Model => Html[Msg] = m => AccountsView(m.accountsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.AccountsMsg(sub) =>
        println("Page.update AccountsMsg = " + sub)
        val (sub2, cmd) = AccountsView.update(model.accountsModel, sub)
        (model.copy(accountsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Accounts page")
  }

  object MaintainAccount extends Page {
    val path: String = "/components/maintain-account"
    val render: Model => Html[Msg] = m =>
      println(s"render = ${m.maintainAccountModel}")
      MaintainAccountView(m.maintainAccountModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainAccountMsg(sub) =>
        val (sub2, cmd) = MaintainAccountView.update(model.maintainAccountModel, sub)
        (model.copy(maintainAccountModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Account page")
  }

  object MaintainMovementSpec extends Page {
    val path:   String             = "/components/maintain-movement-spec"
    val render: Model => Html[Msg] = m => MaintainMovementSpecView(m.maintainMovementSpecModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainMovementSpecsMsg(sub) =>
        val (sub2, cmd) = MaintainMovementSpecView.update(model.maintainMovementSpecModel, sub)
        (model.copy(maintainMovementSpecModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Movement Spec page")
  }

  object ReconSpecs extends Page {
    val path:   String             = "/components/recon-specs"
    val render: Model => Html[Msg] = m => ReconSpecsView(m.reconSpecsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.ReconSpecsMsg(sub) =>
        val (sub2, cmd) = ReconSpecsView.update(model.reconSpecsModel, sub)
        (model.copy(reconSpecsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Recon Specs page")
  }

  object MaintainReconSpec extends Page {
    val path:   String             = "/components/maintain-recon-spec"
    val render: Model => Html[Msg] = m => MaintainReconSpecView(m.maintainReconSpecModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainReconSpecMsg(sub) =>
        val (sub2, cmd) = MaintainReconSpecView.update(model.maintainReconSpecModel, sub)
        (model.copy(maintainReconSpecModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Recon Spec page")
  }

  object Ingest extends Page {
    val path:   String             = "/components/ingest"
    val render: Model => Html[Msg] = m => IngestView(m.ingestModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.IngestViewMsg(sub) =>
        val (sub2, cmd) = IngestView.update(model.ingestModel, sub)
        (model.copy(ingestModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Ingest page")
  }

  object Jobs extends Page {
    val path:   String             = "/components/jobs"
    val render: Model => Html[Msg] = m => JobsView(m.jobsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.JobsMsg(sub) =>
        val (sub2, cmd) = JobsView.update(model.jobsModel, sub)
        (model.copy(jobsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Jobs page") |+| HttpHelper.fetchJobsByType(model.jobsModel.selectedJobType)
  }

  object UnreconciledMovements extends Page {
    val path:   String             = "/components/unreconciled-movements"
    val render: Model => Html[Msg] = m => UnreconciledMovementsView(m.unreconciledMovementsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.UnreconciledMovementsMsg(sub) =>
        val (sub2, cmd) = UnreconciledMovementsView.update(model.unreconciledMovementsModel, sub)
        (model.copy(unreconciledMovementsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Unreconciled Movements page") |+|
        HttpHelper.fetchUnreconciledMovements(model.unreconciledMovementsModel.movementSpec.id.get)
  }

  object Reconciliations extends Page {
    val path:   String             = "/components/reconciliations"
    val render: Model => Html[Msg] = m => ReconciliationsView(m.reconciliationsModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.ReconciliationsMsg(sub) =>
        val (sub2, cmd) = ReconciliationsView.update(model.reconciliationsModel, sub)
        (model.copy(reconciliationsModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Reconciliations page") |+|
        HttpHelper.fetchReconciliationsByReconSpecID(model.reconciliationsModel.reconSpec.id.get)
  }

  object FundFlow extends Page {
    val path:   String             = "/components/fund-flow"
    val render: Model => Html[Msg] = m => FundFlowView(m.fundFlowViewModel)

    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.FundFlowViewMsg(sub) =>
        val (sub2, cmd) = FundFlowView.update(model.fundFlowViewModel, sub)
        (model.copy(fundFlowViewModel = sub2), cmd)
      case _ => (model, Cmd.None)

    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Fund Flow page")
  }
}
