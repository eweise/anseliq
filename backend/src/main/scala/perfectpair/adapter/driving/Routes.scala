package perfectpair.adapter.driving

import com.augustnagro.magnum.*
import com.typesafe.scalalogging.StrictLogging
import perfectpair.common.http.HttpPath
import perfectpair.common.model.*
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.*
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
  reportingService:   ReportingService
) extends cask.MainRoutes,
      StrictLogging:

  val prefixPath = "/api/v1"
  val loginPath  = s"$prefixPath / login"

  val randomMessagePath          = s"$prefixPath / random-message"
  val randomMessage2Path         = s"$prefixPath / random-message2"
  val pingPath                   = s"$prefixPath / scala"
  val subscribeServerMessagePath = s"$prefixPath / subscribe"
  val transactor                 = Transactor(dataSource)

  override def mainDecorators = Seq(corsDecorator())

  @cask.get("api/v1/random-message")
  def getRandomMessage() =
    cask.Response(
      data = write(RandomMessage("random message"))
    )

  @cask.get("api/v1/random-message2")
  def getNextRandomMessage() =
    cask.Response(
      data = write(RandomMessage("next random message"))
    )

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
      val fundFlowId    = UUID.fromString(id)
      val fundFlow      = fundFlowConfigRepo.findById(fundFlowId).ErrIfNotFound("fund flow", fundFlowId)
      val accounts      = accountRepo.findByFundFlow(fundFlowId)
      val movementSpecs = movementSpecRepo.findByFundFlowId(fundFlowId)

      val accountSummaryViews = accounts.map(account =>
        val hasInSpec  = movementSpecs.exists(spec => spec.inAccountId == account.id)
        val hasOutSpec = movementSpecs.exists(spec => spec.outAccountId == account.id)

        val isReconAccount    = hasInSpec && hasOutSpec
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

  @cask.route(s"${HttpPath.account}/:id/unreconciled-movements", methods = HttpCmd.GET)
  def unreconciledMovementsByAccount(id: String): String =
    transact(transactor):
      val movements = movementRepo.findUnReconciledByAccount(UUID.fromString(id))
      val payload   = movements.map(Payload.movementfromDomainModel)
      write(payload)

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

//  @cask.route(s"${HttpPath.movementSpec}/:id/ingest", methods = HttpCmd.POST)
//  def ingestMovementSpec(id: String, request: cask.Request): String =
//    val movementSpecId = UUID.fromString(id)
//    request.exchange.startBlocking()
//    val is = request.exchange.getInputStream
//    transact(transactor):
//      val movementSpec = movementSpecRepo
//        .findById(movementSpecId)
//        .getOrElse(throw NotFoundError(s"MovementSpec $id not found"))
//      jobManager.submitAndProcessJob(JobType.Ingestion, movementSpec.id) { job =>
//        val result = ingestor.ingest(movementSpec,  is)
//        IngestionInfo(
//          numberOfRecordsIngested = result.recordCount,
//          processingTimeSeconds   = result.ingestionTimeSeconds
//        )
//      }
//    write("ingested")

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

  // Disputes
  @cask.route(HttpPath.dispute, methods = HttpCmd.POST)
  def raiseDispute(request: cask.Request): String =
    val payload = read[RaiseDisputeRequest](request.text())
    transact(transactor):
      val dispute = disputeService.raise(
        UUID.fromString(payload.referenceId),
        Payload.disputeTargetToDomain(payload.targetType),
        Payload.disputeCategoryToDomain(payload.category),
        payload.detail
      )
      val (accountId, accountName, referenceLabel) = disputeContext(dispute)
      write(Payload.disputePayloadFromDomain(dispute, accountId, accountName, referenceLabel))

  @cask.route(HttpPath.disputes, methods = HttpCmd.GET)
  def listDisputes(): String =
    transact(transactor):
      val disputes = disputeRepo.findAll
      val payload = disputes.map { d =>
        val (accountId, accountName, referenceLabel) = disputeContext(d)
        Payload.disputePayloadFromDomain(d, accountId, accountName, referenceLabel)
      }
      write(payload)

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
        payload.newStatus.map(Payload.disputeStatusToDomain)
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(disputeDetail(dispute))

  private def disputeDetail(dispute: Dispute): Txn[DisputeDetailPayload] =
    val (accountId, accountName, referenceLabel) = disputeContext(dispute)
    val disputePayload = Payload.disputePayloadFromDomain(dispute, accountId, accountName, referenceLabel)
    val movementPayload = dispute.targetType match
      case DisputeTarget.Movement => movementRepo.findById(dispute.referenceId).map(Payload.movementfromDomainModel)
      case DisputeTarget.Reconciliation => None
    val reconPayload = dispute.targetType match
      case DisputeTarget.Reconciliation => reconRepo.findById(dispute.referenceId).map(Payload.reconFromDomain)
      case DisputeTarget.Movement => None
    val comments = disputeCommentRepo.findByDisputeId(dispute.id).map(Payload.disputeCommentPayloadFromDomain)
    DisputeDetailPayload(disputePayload, movementPayload, reconPayload, comments)

  private def disputeContext(dispute: Dispute): Txn[(String, String, String)] =
    dispute.targetType match
      case DisputeTarget.Movement =>
        val movement = movementRepo.findById(dispute.referenceId).ErrIfNotFound("movement", dispute.referenceId)
        val account  = accountRepo.findById(movement.inAccountId).ErrIfNotFound("account", movement.inAccountId)
        (account.id.toString, account.name, movement.referenceId.toString)
      case DisputeTarget.Reconciliation =>
        val recon     = reconRepo.findById(dispute.referenceId).ErrIfNotFound("reconciliation", dispute.referenceId)
        val reconSpec = reconSpecRepo.findById(recon.specId).ErrIfNotFound("reconciliation spec", recon.specId)
        val account   = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
        (account.id.toString, account.name, recon.description)

  // Reports
  @cask.route(s"${HttpPath.reports}/summary", methods = HttpCmd.GET)
  def getReportsSummary(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val report = reportingService.summary(fundFlowId.map(UUID.fromString))
      write(Payload.reportsSummaryPayloadFromDomain(report))

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

  @cask.get("/account/:id")
  def account(fundFlowName: String, accountIdStr: String): String =
    transact(transactor):
      val accountID = Try {
        UUID.fromString(accountIdStr)
      }.getOrElse(
        throw new Exception("bad uuid")
      )

      val account = accountRepo
        .findById(accountID)
        .getOrElse:
          throw Exception("not found") // todo need proper http errors

      write(Payload.accountPayloadFromDomain(account))

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
      else delegate(ctx, Map.empty).map(response => response.copy(headers = response.headers ++ corsHeaders))

  logger.info("Initializing backend")
  initialize()
