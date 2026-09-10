package onparity.adapter.driving

import com.augustnagro.magnum.*
import com.typesafe.scalalogging.StrictLogging
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.common.util.JwtHelper
import onparity.domain.model.*
import onparity.domain.port.driven.*
import onparity.domain.service.*
import upickle.default.*

import java.util.UUID
import javax.sql.DataSource
import scala.util.Try

object HttpCmd:
  def GET    = Seq("get", "options")
  def POST   = Seq("post", "options")
  def PUT    = Seq("put", "options")
  def DELETE = Seq("delete", "options")

class App(using
  dataSource:         DataSource,
  accountRepo:        AccountRepo,
  reconRepo:          ReconciliationRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
  movementSpecRepo:   MovementSpecRepo,
  fileFeedRepo:       FileFeedRepo,
  movementRepo:       MovementRepo,
  fundFlowOperations: FundFlowOperations,
  jobManager:         JobManager,
  ingestor:           Ingestor,
  reconciler:         InMemoryReconciler,
  manualReconciler:   ManualReconciler,
  jobRepo:            JobRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  eventRepo:          EventRepo,
  disputeRepo:        DisputeRepo,
  disputeCommentRepo: DisputeCommentRepo,
  disputeService:     DisputeService,
  reportingService:   ReportingService,
  fileInventoryRepo:  FileInventoryRepo,
  userRepo:           UserRepo,
  userService:        UserService,
  authService:        AuthService,
  jwtSecret:          JwtSecret
) extends cask.MainRoutes,
      StrictLogging:

  val prefixPath                 = "/api/v1"
  val pingPath                   = s"$prefixPath / scala"
  val subscribeServerMessagePath = s"$prefixPath / subscribe"
  val transactor                 = Transactor(dataSource)

  override def port: Int = sys.env.get("CASK_PORT").map(_.toInt).getOrElse(8080)

  // authDecorator must come after corsDecorator: a CORS preflight (OPTIONS) request is
  // short-circuited by corsDecorator before it ever reaches authDecorator, so preflight requests
  // never need a token. Order also determines which curried parameter list a decorator's injected
  // map lands in for a route that declares one (e.g. `(currentUser: UserClaim)`) — authDecorator
  // must be last so its map is the one bound there.
  override def mainDecorators = Seq(corsDecorator(), authDecorator(jwtSecret))

  @cask.route(s"${HttpPath.fundFlows}/:fundFlowID/accounts", methods = HttpCmd.GET)
  def accountsByFundFlowID(fundFlowID: String): String =
    transact(transactor):
      val accounts = accountRepo.findByFundFlow(UUID.fromString(fundFlowID))
      val payload  = accounts.map(Payload.accountPayloadFromDomain)
      write(payload)

  @cask.route(HttpPath.accounts, methods = HttpCmd.GET)
  def allAccounts(): String =
    transact(transactor):
      val accounts = accountRepo.findAll()
      val payload  = accounts.map(Payload.accountPayloadFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.reconSpec}/:reconSpecID/reconciliations", methods = HttpCmd.GET)
  def movementsByReconciliationSpec(reconSpecID: String): String =
    transact(transactor):
      val reconciliations = reconRepo.findByReconSpecID(UUID.fromString(reconSpecID))
      val payload         = reconciliations.map(Payload.reconFromDomain)
      write(payload)

  // Fund Flows
  @cask.route(HttpPath.fundFlows, methods = HttpCmd.GET)
  def getFundFlows() =
    transact(transactor):
      val fundFlows = fundFlowConfigRepo.findAll
      val payloads  = fundFlows.map(ff => Payload.fundFlowPayloadFromDomain(ff))
      write(payloads)

  @cask.route(s"${HttpPath.fundFlow}/:id/summary", methods = HttpCmd.GET)
  def getFundFlowMainView(id: String) =
    transact(transactor):
      val fundFlowId = UUID.fromString(id)
      val fundFlow   = fundFlowConfigRepo.findById(fundFlowId).ErrIfNotFound("fund flow", fundFlowId)
      val accounts   = accountRepo.findByFundFlow(fundFlowId)

      val accountSummaryViews = accounts.map(account =>
        val isReconAccount    = account.accountType == AccountType.Recon
        val unreconciledCount = if isReconAccount then movementRepo.countUnreconciledByAccount(account.id) else 0

        AccountSummaryViewPayload(
          Payload.accountPayloadFromDomain(account),
          isReconAccount    = isReconAccount,
          unreconciledCount = unreconciledCount
        )
      )
      val fundFlowSummaryView = FundFlowSummaryViewPayload(
        fundFlow = Payload.fundFlowPayloadFromDomain(fundFlow),
        accounts = accountSummaryViews
      )
      write(fundFlowSummaryView)

  @cask.route(s"${HttpPath.fundFlow}", methods = HttpCmd.POST)
  def createFundFlow(request: cask.Request): String =
    val payload = read[FundFlowPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.createFundFlow(payload))

  @cask.route(s"${HttpPath.fundFlow}/:id", methods = HttpCmd.PUT)
  def updateFundFlow(id: String, request: cask.Request): String =
    val payload = read[FundFlowPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.updateFundFlow(payload))

  // methods = Seq("get"), not HttpCmd.GET: updateAccount (PUT, below) shares this same path and
  // already registers "options" there via HttpCmd.PUT — cask allows only one "options" registration
  // per path, same reasoning as listUsers/createUser further down.
  @cask.route(s"${HttpPath.account}/:id", methods = Seq("get"))
  def getAccount(id: String, request: cask.Request): String =
    transact(transactor):
      write(accountRepo.findById(UUID.fromString(id)).map(Payload.accountPayloadFromDomain))

  @cask.route(s"${HttpPath.account}", methods = HttpCmd.POST)
  def createAccount(request: cask.Request): String =
    val accountRequest = read[AccountPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.createAccount(accountRequest))

  @cask.route(s"${HttpPath.account}/:id", methods = HttpCmd.PUT)
  def updateAccount(id: String, request: cask.Request): String =
    val accountRequest = read[AccountPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.updateAccount(id, accountRequest))

  // MovementSpec
  @cask.route(s"${HttpPath.fundFlows}/:fundFlowID/movement-specs", methods = HttpCmd.GET)
  def movementSpecsByFundFlowID(fundFlowID: String): String =
    transact(transactor):
      val specs   = movementSpecRepo.findByFundFlowId(UUID.fromString(fundFlowID))
      val payload = specs.map(Payload.movementSpecPayloadFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.movements}/:movementSpecID/unreconciled", methods = HttpCmd.GET)
  def movementsBySpecID(movementSpecID: String): String =
    val specID = UUID.fromString(movementSpecID)
    transact(transactor):
      val movements = movementRepo.findUnReconciledByMovementSpec(specID)
      val payload   = movements.map(Payload.movementfromDomainModel)
      write(payload)

  @cask.route(s"${HttpPath.movements}/search", methods = HttpCmd.POST)
  def searchMovements(request: cask.Request): String =
    val req = read[MovementSearchRequest](request.text())
    transact(transactor):
      val criteria = MovementSearchCriteria(
        fundFlowId       = UUID.fromString(req.fundFlowId),
        accountId        = req.accountId.map(UUID.fromString),
        amount           = req.amount,
        movementSpecId   = req.movementSpecId.map(UUID.fromString),
        properties       = req.properties,
        fileSourceQuery  = req.fileSourceQuery,
        unreconciledOnly = req.unreconciledOnly,
        createdFrom      = req.createdFrom,
        createdTo        = req.createdTo,
        movementType     = req.movementType.map(MovementType.valueOf)
      )
      val results = movementRepo.search(criteria)
      // Names resolved via the fund flow's full account/movement-spec lists (both already
      // fund-flow-scoped, cheap fetches) rather than accountRepo.findAllById/
      // movementSpecRepo.findAllById on just the ids referenced by results — Magnum's default
      // findAllById binds a Postgres array parameter via JDBC's createArrayOf, which throws
      // ("Unable to find server array type for provided name OTHER") for a UUID id column against
      // this pgjdbc version. Fetching the whole fund flow's accounts/specs sidesteps that
      // entirely and is simpler than working around the array-binding bug.
      val accountNames = accountRepo.findByFundFlow(criteria.fundFlowId).map(a => a.id -> a.name).toMap
      val movSpecNames = movementSpecRepo.findByFundFlowId(criteria.fundFlowId).map(s => s.id -> s.name).toMap
      write(results.map(m => Payload.movementSearchResultPayloadFromDomain(m, accountNames, movSpecNames)))

  @cask.route(s"${HttpPath.account}/:id/reconciliations", methods = HttpCmd.GET)
  def reconciliationsByAccount(id: String, limit: Int = 100): String =
    transact(transactor):
      val reconciliations = reconRepo.findByAccountID(UUID.fromString(id), limit)
      write(reconciliations.map(Payload.reconFromDomain))

  @cask.route(HttpPath.login, methods = HttpCmd.POST)
  def login(request: cask.Request): String =
    val payload = read[LoginRequest](request.text())
    transact(transactor):
      authService.login(payload.email, payload.password) match
        case Right(token) => write(LoginSuccess(accessToken = token, refreshToken = ""))
        case Left(msg)    => throw UnauthorizedError(msg)

  @cask.route(HttpPath.users, methods = HttpCmd.GET)
  def listUsers()(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    transact(transactor):
      write(userService.listUsers().map(Payload.userPayloadFromDomain))

  // Unlike listUsers above, any authenticated user can call this — it backs the dispute assignment
  // picker on the Exceptions page, not user administration. Viewers are excluded: they're read-only
  // by design, so a dispute can never be handed to one.
  @cask.route(HttpPath.assignableUsers, methods = HttpCmd.GET)
  def listAssignableUsers()(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      write(
        userService.listUsers().filter(u => u.active && u.role != UserRole.Viewer).map(Payload.userPayloadFromDomain)
      )

  // methods = Seq("post"), not HttpCmd.POST: listUsers (above) already registers "options" at this
  // exact path (HttpCmd.GET includes it too, for CORS preflight) — cask errors on two endpoints
  // sharing the same method+path pair, so a second "options" registration here would collide.
  @cask.route(HttpPath.users, methods = Seq("post"))
  def createUser(request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[CreateUserRequest](request.text())
    transact(transactor):
      val user = userService.createUser(
        payload.email,
        payload.firstName,
        payload.lastName,
        UserRole.valueOf(payload.role),
        payload.password
      )
      write(Payload.userPayloadFromDomain(user))

  @cask.route(s"${HttpPath.users}/:id", methods = HttpCmd.PUT)
  def updateUser(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[UpdateUserRequest](request.text())
    transact(transactor):
      val user = userService.updateUser(
        UUID.fromString(id),
        payload.firstName,
        payload.lastName,
        UserRole.valueOf(payload.role),
        payload.active
      )
      write(Payload.userPayloadFromDomain(user))

  @cask.route(s"${HttpPath.users}/:id/reset-password", methods = HttpCmd.POST)
  def resetUserPassword(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[ResetPasswordRequest](request.text())
    transact(transactor):
      userService.resetPassword(UUID.fromString(id), payload.newPassword)
    write("ok")

  private def requireAdmin(currentUser: JwtHelper.UserClaim): Unit =
    if currentUser.role != UserRole.Admin.toString then throw ForbiddenError("Admin role required")

  // FileFeed — owns "where the file comes from and how to parse it"; a MovementSpec routes off
  // one of these to claim the rows for one account pair. Not scoped to a fund flow (a feed has no
  // notion of which fund flow it belongs to until a MovementSpec attaches accounts to it), so this
  // lists every feed rather than joining through accounts the way movement-specs-by-fund-flow does.
  @cask.route(s"${HttpPath.fileFeeds}", methods = HttpCmd.GET)
  def listFileFeeds(): String =
    transact(transactor):
      write(fileFeedRepo.findAll.map(Payload.fileFeedPayloadFromDomain))

  @cask.route(s"${HttpPath.fileFeed}", methods = HttpCmd.POST)
  def createFileFeed(request: cask.Request): String =
    val payload = read[FileFeedPayload](request.text())
    validateDataSourceConfig(payload)
    transact(transactor):
      write(fundFlowOperations.createFileFeed(payload))

  @cask.route(s"${HttpPath.fileFeed}/:id", methods = HttpCmd.PUT)
  def updateFileFeed(id: String, request: cask.Request): String =
    val payload = read[FileFeedPayload](request.text())
    validateDataSourceConfig(payload)
    transact(transactor):
      write(fundFlowOperations.updateFileFeed(id, payload))

  // Re-checks the same rules the frontend validates inline (DataSourceConfig.validate, in common)
  // as a safety net against a bypassed or buggy frontend — not the primary UX for catching mistakes.
  private def validateDataSourceConfig(payload: FileFeedPayload): Unit =
    payload.dataSourceConfig.foreach { ds =>
      val errors = ds.validate
      if errors.nonEmpty then throw IllegalArgumentError("dataSourceConfig", errors.mkString("; "))
    }

  @cask.route(s"${HttpPath.fileFeed}/:id/test-parse", methods = HttpCmd.POST)
  def testParseFileFeed(id: String, request: cask.Request): String =
    val content = request.text()
    transact(transactor):
      val fileFeedId = UUID.fromString(id)
      val fileFeed   = fileFeedRepo.findById(fileFeedId).ErrIfNotFound("FileFeed", fileFeedId)
      val result     = ingestor.testParse(fileFeed, content)
      write(Payload.parseTestResultPayloadFromDomain(result))

  @cask.delete(s"${HttpPath.fileFeed}/:id")
  def deleteFileFeed(id: String): String =
    transact(transactor):
      fundFlowOperations.deleteFileFeed(id)
    write("deleted")

  // MovementSpec
  @cask.route(s"${HttpPath.movementSpec}", methods = HttpCmd.POST)
  def createMovementSpec(request: cask.Request): String =
    val payload = read[MovementSpecPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.createMovementSpec(payload))

  @cask.route(s"${HttpPath.movementSpec}/:id", methods = HttpCmd.PUT)
  def updateMovementSpec(id: String, request: cask.Request): String =
    val payload = read[MovementSpecPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.updateMovementSpec(id, payload))

  @cask.delete(s"${HttpPath.movementSpec}/:id")
  def deleteMovementSpec(id: String): String =
    transact(transactor):
      fundFlowOperations.deleteMovementSpec(id)
    write("deleted")

  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reoncileAccount(id: String, request: cask.Request): String =
    val accountId = UUID.fromString(id)
    val (account, job) = transact(transactor):
      val account = accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $id not found"))
      val jobId   = jobManager.submitJob(JobType.Reconciliation, accountId)
      val job     = jobRepo.findById(jobId).ErrIfNotFound("Job", jobId)
      jobRepo.update(job.copy(jobStatus = JobStatus.InProgress))
      (account, job)

    val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

    transact(transactor):
      val jobInfo = ReconciliationInfo(
        accountId                   = account.id.toString,
        accountName                 = account.name,
        reconciliationsCreatedCount = reconcilationResult.totalCount,
        processingTimeSeconds       = reconcilationResult.processingTimeSeconds
      )
      jobManager.completeJob(job.id, jobInfo)
    write("reconciled")

  @cask.route(s"${HttpPath.reconSpec}/:id/manual-match", methods = HttpCmd.POST)
  def manualMatch(id: String, request: cask.Request): String =
    val payload = read[ManualMatchRequest](request.text())
    transact(transactor):
      val reconciliation = manualReconciler.matchMovements(
        UUID.fromString(id),
        payload.inMovementIds.map(UUID.fromString),
        payload.outMovementIds.map(UUID.fromString),
        payload.description
      )
      write(Payload.reconFromDomain(reconciliation))

  @cask.route(s"${HttpPath.reconciliation}/:id", methods = HttpCmd.GET)
  def getReconciliationDetail(id: String): String =
    transact(transactor):
      val reconId = UUID.fromString(id)
      val recon   = reconRepo.findById(reconId).ErrIfNotFound("reconciliation", reconId)
      val spec = recon.specId.map: specId =>
        val spec    = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
        val account = accountRepo.findById(spec.accountId).ErrIfNotFound("account", spec.accountId)
        (spec, account.name)
      val movements = movementRepo.findByReconciliationID(reconId)
      write(Payload.reconciliationDetailPayloadFromDomain(recon, spec, movements))

  @cask.route(s"${HttpPath.movement}/:id", methods = HttpCmd.GET)
  def getMovementDetail(id: String): String =
    transact(transactor):
      val movementId   = UUID.fromString(id)
      val m            = movementRepo.findById(movementId).ErrIfNotFound("movement", movementId)
      val outAccount   = accountRepo.findById(m.outAccountId).ErrIfNotFound("account", m.outAccountId)
      val inAccount    = accountRepo.findById(m.inAccountId).ErrIfNotFound("account", m.inAccountId)
      val movementSpec = movementSpecRepo.findById(m.movementSpecId).ErrIfNotFound("movement spec", m.movementSpecId)
      write(Payload.movementDetailPayloadFromDomain(m, outAccount.name, inAccount.name, movementSpec.name))

  @cask.route(s"${HttpPath.reconciliation}/:id/unmatch", methods = HttpCmd.POST)
  def unmatchReconciliation(id: String): String =
    transact(transactor):
      manualReconciler.unmatch(UUID.fromString(id))
    write("voided")

  @cask.route(s"${HttpPath.reconciliation}/:id/write-off", methods = HttpCmd.POST)
  def writeOffReconciliation(id: String, request: cask.Request): String =
    val payload = read[WriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.writeOff(UUID.fromString(id), payload.reason)
    write("written-off")

  @cask.route(s"${HttpPath.movements}/bad-file-preview", methods = HttpCmd.POST)
  def previewBadFile(request: cask.Request): String =
    val payload = read[BadFilePreviewRequest](request.text())
    transact(transactor):
      write(Payload.badFilePreviewPayloadFromDomain(manualReconciler.previewBadFile(payload.fileSourceEntry)))

  // Not wrapped in transact(...): fixBadFile manages its own batched transactions internally (same
  // reason reoncileAccount above calls reconciler.reconcileAccountResumable bare) — a very dirty
  // file shouldn't need one unbounded transaction to fix.
  @cask.route(s"${HttpPath.movements}/bad-file-fix", methods = HttpCmd.POST)
  def fixBadFile(request: cask.Request): String =
    val payload = read[FixBadFileRequest](request.text())
    val result  = manualReconciler.fixBadFile(payload.fileSourceEntry)
    write(Payload.fixBadFileResultPayloadFromDomain(result))

  // Disputes
  @cask.route(HttpPath.dispute, methods = HttpCmd.POST)
  def raiseDispute(request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    val payload = read[RaiseDisputeRequest](request.text())
    transact(transactor):
      val dispute = disputeService.raise(
        UUID.fromString(payload.referenceId),
        Payload.disputeTargetToDomain(payload.targetType),
        Payload.disputeCategoryToDomain(payload.category),
        payload.detail,
        raisedByUserId = Some(UUID.fromString(currentUser.userId))
      )
      write(disputePayloadWithNames(dispute))

  @cask.route(HttpPath.disputes, methods = HttpCmd.GET)
  def listDisputes(): String =
    transact(transactor):
      write(disputeRepo.findAll.map(disputePayloadWithNames))

  @cask.route(s"${HttpPath.dispute}/:id", methods = HttpCmd.GET)
  def getDispute(id: String): String =
    transact(transactor):
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  @cask.route(s"${HttpPath.dispute}/:id/comments", methods = HttpCmd.POST)
  def addDisputeComment(id: String, request: cask.Request): String =
    val payload = read[AddDisputeCommentRequest](request.text())
    transact(transactor):
      disputeService.addComment(
        UUID.fromString(id),
        payload.body,
        payload.newStatus.map(Payload.disputeStatusToDomain),
        payload.newAssignedToUserId.map(UUID.fromString)
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  private def disputeDetail(dispute: Dispute): Txn[DisputeDetailPayload] =
    val disputePayload = disputePayloadWithNames(dispute)
    val movementPayload = dispute.targetType match
      case DisputeTarget.Movement       => movementRepo.findById(dispute.referenceId).map(Payload.movementfromDomainModel)
      case DisputeTarget.Reconciliation => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val reconPayload = dispute.targetType match
      case DisputeTarget.Reconciliation => reconRepo.findById(dispute.referenceId).map(Payload.reconFromDomain)
      case DisputeTarget.Movement       => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val comments = disputeCommentRepo
      .findByDisputeId(dispute.id)
      .map(c => Payload.disputeCommentPayloadFromDomain(c, userDisplay(c.newAssignedToUserId)))
    DisputeDetailPayload(disputePayload, movementPayload, reconPayload, comments)

  private def disputePayloadWithNames(dispute: Dispute): Txn[DisputePayload] =
    val (accountId, accountName, referenceLabel) = disputeContext(dispute)
    Payload.disputePayloadFromDomain(
      dispute,
      accountId,
      accountName,
      referenceLabel,
      raisedByName   = userDisplay(dispute.raisedByUserId),
      assignedToName = userDisplay(dispute.assignedToUserId)
    )

  private def disputeContext(dispute: Dispute): Txn[(String, String, String)] =
    dispute.targetType match
      case DisputeTarget.Movement =>
        val movement = movementRepo.findById(dispute.referenceId).ErrIfNotFound("movement", dispute.referenceId)
        val account  = accountRepo.findById(movement.inAccountId).ErrIfNotFound("account", movement.inAccountId)
        (account.id.toString, account.name, movement.referenceId.toString)
      case DisputeTarget.Reconciliation =>
        // recon.specId is only ever None for a system-generated Reversed reconciliation, and
        // nothing ever raises a dispute against one (dispute-raising only happens on an InProgress
        // mismatch from matchMovements/streamMergeJoin, neither of which Ledger.reverseMovement
        // goes through) — so this ErrIfNotFound is a correctness assertion, not an expected path.
        val recon     = reconRepo.findById(dispute.referenceId).ErrIfNotFound("reconciliation", dispute.referenceId)
        val specId    = recon.specId.ErrIfNotFound("reconciliation spec id", recon.id)
        val reconSpec = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
        val account   = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
        (account.id.toString, account.name, recon.description)
      case DisputeTarget.MovementSpec =>
        val spec    = movementSpecRepo.findById(dispute.referenceId).ErrIfNotFound("movement spec", dispute.referenceId)
        val account = accountRepo.findById(spec.inAccountId).ErrIfNotFound("account", spec.inAccountId)
        (account.id.toString, account.name, spec.name)
      case DisputeTarget.FileFeed =>
        // A feed can route to several accounts (a multi-account BAI-style feed), so there's no
        // single account to attribute a FileMissing/UnroutedRecord dispute to — accountId stays
        // blank rather than picking one arbitrarily.
        val feed = fileFeedRepo.findById(dispute.referenceId).ErrIfNotFound("file feed", dispute.referenceId)
        ("", feed.name, feed.name)

  private def userDisplay(userId: Option[UUID]): Txn[Option[String]] =
    userId.flatMap(id => userRepo.findById(id)).map(u => s"${u.firstName} ${u.lastName}")

  // Quick "assign to me" shortcut — goes through the same addComment path as any other assignment
  // (so it's still recorded on the thread and still subject to the Viewer role check), just with no
  // message and the current user as the target.
  @cask.route(s"${HttpPath.dispute}/:id/assign", methods = HttpCmd.POST)
  def assignDisputeToMe(id: String)(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      disputeService.addComment(
        UUID.fromString(id),
        body                = None,
        newStatus           = None,
        newAssignedToUserId = Some(UUID.fromString(currentUser.userId))
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  // Reports
  @cask.route(s"${HttpPath.reports}/summary", methods = HttpCmd.GET)
  def getReportsSummary(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val report = reportingService.summary(fundFlowId.map(UUID.fromString))
      write(Payload.reportsSummaryPayloadFromDomain(report))

  @cask.route(s"${HttpPath.reports}/trend", methods = HttpCmd.GET)
  def getReportsTrend(fundFlowId: Option[String] = None, days: Int = 30): String =
    transact(transactor):
      val report = reportingService.trend(fundFlowId.map(UUID.fromString), days)
      write(Payload.trendReportPayloadFromDomain(report))

  // Drill-through list backing the aging chart's click-through — same fundFlowId scoping as
  // /reports/summary (omitted -> every fund flow), one row per (recon account, unreconciled
  // movement).
  @cask.route(s"${HttpPath.reports}/unreconciled-movements", methods = HttpCmd.GET)
  def getReportsUnreconciledMovements(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val rows = reportingService.unreconciledMovements(fundFlowId.map(UUID.fromString))
      write(rows.map(Payload.unreconciledMovementPayloadFromDomain))

  // Jobs
  @cask.route(s"${HttpPath.jobs}/:jobType", methods = HttpCmd.GET)
  def jobsByType(jobType: String): String =
    val jobTypeInfo =
      try JobTypeInfo.valueOf(jobType)
      catch case ex: Exception => throw IllegalArgumentError("JobType", jobType)
    transact(transactor):
      val jobs = jobRepo.findByJobType(JobType.valueOf(jobTypeInfo.toString))
      write(jobs.map(Payload.jobPayloadFromDomain))

  // Events
  @cask.route(s"${HttpPath.events}/:eventType", methods = HttpCmd.GET)
  def getEvents(eventType: String): String =
    transact(transactor):
      write(eventRepo.findByEventType(eventType).map(Payload.eventPayloadFromDomain))

  // File Inventory
  @cask.route(s"${HttpPath.fileInventory}", methods = HttpCmd.GET)
  def getFileInventory(): String =
    transact(transactor):
      val entries = fileInventoryRepo.findAllWithFileFeedName()
      write(entries.map((fi, fileFeedName) => Payload.fileInventoryPayloadFromDomain(fi, fileFeedName)))

  // ReconSpec
  @cask.route(s"${HttpPath.account}/:accountID/recon-specs", methods = HttpCmd.GET)
  def reconSpecsByaccountID(accountID: String): String =
    transact(transactor):
      val specs = reconSpecRepo.findByAccountID(UUID.fromString(accountID))
      specs.foreach(s => logger.debug(s"[reconSpecsByaccountID] id=${s.id} ruleJson=${s.ruleJson} rule=${s.rule()}"))
      val payload = specs.map(Payload.reconSpecPayloadFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.reconSpec}", methods = HttpCmd.POST)
  def createReconSpec(request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.createReconSpec(payload))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = HttpCmd.PUT)
  def updateReconSpec(id: String, request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    transact(transactor):
      write(fundFlowOperations.updateReconSpec(id, payload))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = Seq("delete"))
  def deleteReconSpec(id: String): String =
    transact(transactor):
      fundFlowOperations.deleteReconSpec(id)
    write("deleted")

  @cask.get("/account/:id/movements")
  def movements(fundFlowName: String, accountIdStr: String): String =
    val accountID = Try {
      UUID.fromString(accountIdStr)
    }.getOrElse(
      throw new Exception("bad uuid")
    )

    transact(transactor):
      val adapterMovements = movementRepo
        .findByAccount(accountID)
        .map(Payload.movementfromDomainModel)

      write(adapterMovements.toSeq)

  class corsDecorator extends cask.RawDecorator:
    def wrapFunction(ctx: cask.model.Request, delegate: Delegate) =
      val corsHeaders = Seq(
        "Access-Control-Allow-Origin"  -> "*",
        "Access-Control-Allow-Methods" -> "GET, POST, DELETE, PUT, OPTIONS, HEAD",
        "Access-Control-Allow-Headers" -> "Authorization, Content-Type, Accept",
        "Access-Control-Max-Age"       -> "86400"
      )
      if ctx.exchange.getRequestMethod.toString == "OPTIONS" then
        cask.router.Result.Success(
          cask.Response("", statusCode = 200, headers = corsHeaders)
        )
      else
        // An uncaught exception from a route (e.g. login's UnauthorizedError) propagates as a real
        // JVM throw straight through this delegate call, past the `.map` below (which only ever
        // runs on the success path) — cask's own top-level handler is what finally catches it and
        // renders the plain-text 500, entirely outside this decorator, so the response it builds
        // never gets corsHeaders merged in. The browser then can't read that response cross-origin
        // and fetch() fails with a generic "TypeError: Failed to fetch", masking the real error as
        // what looks like a network problem. Catching here so every response — success or error —
        // carries the same CORS headers.
        try delegate(ctx, Map.empty).map(response => response.copy(headers = response.headers ++ corsHeaders))
        catch
          case e: Throwable =>
            logger.error("Unhandled error", e)
            cask.router.Result.Success(
              cask.Response(Option(e.getMessage).getOrElse(e.toString), statusCode = 500, headers = corsHeaders)
            )

  // Applied globally (see mainDecorators above) so every route requires a valid Bearer token
  // except /login itself (can't require a token to get a token). A route that needs to know who's
  // calling declares an extra curried parameter list — e.g. `(currentUser: JwtHelper.UserClaim)` —
  // which cask binds from this decorator's injected map; routes that don't declare it are
  // unaffected (cask only reads as many decorator-contributed param lists as a method declares).
  class authDecorator(jwtSecret: JwtSecret) extends cask.RawDecorator:
    def wrapFunction(ctx: cask.model.Request, delegate: Delegate) =
      if ctx.exchange.getRequestPath == HttpPath.login then delegate(ctx, Map.empty)
      else
        val bearerToken = ctx.headers.get("authorization").flatMap(_.headOption).collect { case s"Bearer $token" =>
          token
        }
        bearerToken.flatMap(t => JwtHelper.decode(t, jwtSecret.value).toOption) match
          case None        => cask.router.Result.Success(cask.Response("Unauthorized", statusCode = 401))
          case Some(claim) => delegate(ctx, Map("currentUser" -> claim))

  logger.info("Initializing backend")
  initialize()
