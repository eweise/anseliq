package perfectpair.app

import com.augustnagro.magnum.*
import com.typesafe.config.ConfigFactory
import ox.*
import ox.scheduling.{Schedule, repeat}
import perfectpair.adapter.driven.*
import perfectpair.adapter.driving.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.*
import perfectpair.common.model.IngestionDataSource.Manual
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.*
import upickle.default.*

import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration.DurationInt
import scala.util.Try

object HttpCmd:
  def GET    = Seq("get", "options")
  def POST   = Seq("post", "options")
  def PUT    = Seq("put", "options")
  def DELETE = Seq("delete", "options")

object Main:

  val shutdown = java.util.concurrent.CountDownLatch(1)

  def main(args: Array[String]): Unit =

    given DataSource = DbDataSource.create(ConfigFactory.load("application.conf"))
    given clock:              ClockService           = ClockService()
    given jobRepo:            JobRepo                = JobRepoLive()
    given jobManager:         JobManager             = JobManager()
    given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
    given reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
    given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
    given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
    given accountRepo:        AccountRepo            = AccountRepoLive()
    given movementRepo:       MovementRepo           = MovementRepoLive()
    given eventRepo:          EventRepo              = EventRepoLive()
    given fundFlowOperations: FundFlowOperations     = FundFlowOperations()
    given eventStore:         EventStore             = EventStore()
    given fileInventoryRepo:  FileInventoryRepo      = FileInventoryRepoLive()
    given fileSourceFactory:  FileSourceFactory      = S3FileSourceFactory()
    given ingestor:           Ingestor               = Ingestor()
    given reconciler:         InMemoryReconciler     = InMemoryReconciler()

    Runtime.getRuntime.addShutdownHook(Thread(() => shutdown.countDown()))

    supervised:

      /**
       * RECONCILIATION: Job looks for Reconciliation jobs. If found then tries to reconcile by the
       * Account ID
       */
      fork:
        try
          jobManager.pollForJob(JobType.Reconciliation, 5) { job =>
            try
              transact(Transactor(dataSource = given_DataSource)):
                val account =
                  accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))
                val reconcilationResult = reconciler.reconcileAccount(account.id)

                val jobInfo = ReconciliationInfo(
                  accountId                   = account.id.toString,
                  accountName                 = account.name,
                  reconciliationsCreatedCount = reconcilationResult.totalCount,
                  processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                )
                jobManager.completeJob(job.id, jobInfo)
            catch case ex: Exception => ex.printStackTrace()
          }
        catch
          case ex: Exception =>
            println("failed poll for Reconciliation job")
            ex.printStackTrace()

      /**
       * INGESTION. Listens for New Files. If found then tries to ingest
       */
      fork:
        try
          eventStore.consumeEvent(classOf[NewFileDetected], 5, 10): event =>
            println(s"found NewFileDetected event: $event")

            val fileInventory = fileInventoryRepo
              .findById(event.fileInventoryID)
              .ErrIfNotFound("FileInventory", event.fileInventoryID)

            ingestor.ingestFileFromInventory(fileInventory.id)
        catch
          case ex: Exception =>
            println("failed eventStore.consumeEvent")
            ex.printStackTrace()

      /**
       * Create Reconciliation Events: Listens for Ingestion and publishes Reconciliation jobs
       */
      fork:
        try
          eventStore.consumeEvent(classOf[MoneyMovementsIngested], 5, 10) { event =>
            println(s"found ingestion job $event")
            val movementSpec = movementSpecRepo
              .findById(event.movementSpecID)
              .ErrIfNotFound("movementSpec", event.movementSpecID)

            transact(Transactor(dataSource = given_DataSource)):
              if (accountRepo.isReconAccount(movementSpec.inAccountId)) then
                jobManager.submitJob(JobType.Reconciliation, movementSpec.inAccountId)

              if (accountRepo.isReconAccount(movementSpec.outAccountId)) then
                jobManager.submitJob(JobType.Reconciliation, movementSpec.outAccountId)
          }
        catch case ex: Exception => ex.printStackTrace()

      /**
       * Publishes FileInventory jobs
       */
      fork:
        val fileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        repeat(Schedule.fixedInterval(1.minute)) {
          try
            transact(Transactor(dataSource = given_DataSource)):
              jobManager.submitJob(JobType.CreateFileInventory, fileInventoryScanId)
          catch
            case ex: Exception =>
              println(s"Error submitting CreateFileInventory job, will retry in 1m: ${ex.getMessage}")
        }

      val s3FileSourceFactory = S3FileSourceFactory()

      /**
       * Consumes FileInventory jobs. Detects new files and creates FileInventory events.
       */
      fork:
        try
          jobManager.pollForJob(JobType.CreateFileInventory, 60): job =>
            transact(Transactor(dataSource = given_DataSource)):
              var filesQueued = 0

              movementSpecRepo.findAll.foreach: spec =>
                val s3FileSource = s3FileSourceFactory.createFileSource(spec.dataSourceConfig().connectionConfig)
                val newFiles     = ingestor.detectAndQueue(spec.id, s3FileSource)
                println(s"File inventory scan: queued ${newFiles.size} new S3 files")
                filesQueued += newFiles.size

              jobManager.completeJob(job.id, FileInventoryInfo(filesQueued = filesQueued))

        catch case ex: Exception => ex.printStackTrace()

      new App().main(args)
      shutdown.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled

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
  jobRepo:            JobRepo,
  reconSpecRepo:      ReconciliationSpecRepo
) extends cask.MainRoutes:

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

      // determine which account is a recon account. must have an in and out
      // movement spec
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

  @cask.route(s"${HttpPath.movementSpec}/:id", methods = Seq("delete"))
  def deleteMovementSpec(id: String): String =
    transact(transactor):
      fundFlowOperations.deleteMovementSpec(id)
    write("deleted")

  @cask.route(s"${HttpPath.movementSpec}/:id/ingest", methods = HttpCmd.POST)
  def ingestMovementSpec(id: String, request: cask.Request): String =
    val movementSpecId = UUID.fromString(id)
    request.exchange.startBlocking()
    val is = request.exchange.getInputStream
    transact(transactor):
      val movementSpec = movementSpecRepo
        .findById(movementSpecId)
        .getOrElse(throw NotFoundError(s"MovementSpec $id not found"))
      jobManager.submitAndProcessJob(JobType.Ingestion, movementSpec.id) { job =>
        val result = ingestor.ingest(movementSpec, is)
        IngestionInfo(
          movementSpecId          = id,
          dataSource              = Manual,
          numberOfRecordsIngested = result.recordCount,
          processingTimeSeconds   = result.ingestionTimeSeconds
        )
      }
    write("ingested")

  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reoncileAccount(id: String, request: cask.Request): String =
    val accountId = UUID.fromString(id)
    transact(transactor):
      val account = accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $id not found"))
      jobManager.submitAndProcessJob(JobType.Reconciliation, accountId) { job =>
        val reconcilationResult = reconciler.reconcileAccount(account.id)
        ReconciliationInfo(
          accountId                   = account.id.toString,
          accountName                 = account.name,
          reconciliationsCreatedCount = reconcilationResult.totalCount,
          processingTimeSeconds       = reconcilationResult.processingTimeSeconds
        )
      }
    write("reconciled")

  // Jobs
  @cask.route(s"${HttpPath.jobs}/:jobType", methods = HttpCmd.GET)
  def jobsByType(jobType: String): String =
    val jobTypeInfo =
      try JobTypeInfo.valueOf(jobType)
      catch case ex: Exception => throw IllegalArgumentError("JobType", jobType)
    transact(transactor):
      val jobs = jobRepo.findByJobType(JobType.valueOf(jobTypeInfo.toString))
      write(jobs.map(Payload.jobPayloadFromDomain))

  // ReconSpec
  @cask.route(s"${HttpPath.account}/:accountID/recon-specs", methods = HttpCmd.GET)
  def reconSpecsByaccountID(accountID: String): String =
    transact(transactor):
      val specs   = reconSpecRepo.findByAccountID(UUID.fromString(accountID))
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

  println("Initializing backend")
  initialize()
