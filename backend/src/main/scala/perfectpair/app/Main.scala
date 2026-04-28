package perfectpair.app

import com.augustnagro.magnum.*
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.adapter.driving.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.*
import perfectpair.common.model.IngestionDataSource.Manual
import perfectpair.domain.model.*
import perfectpair.domain.service.*
import upickle.default.*

import java.util.UUID
import scala.util.Try

object HttpCmd:
  def GET    = Seq("get", "options")
  def POST   = Seq("post", "options")
  def PUT    = Seq("put", "options")
  def DELETE = Seq("delete", "options")

object App extends cask.MainRoutes:
  val dataSource         = DbDataSource.create(ConfigFactory.load("application.conf"))
  val transactor         = Transactor(dataSource)
  val fundFlowConfigRepo = FundFlowConfigRepoLive()
  val accountRepo        = AccountRepoLive()
  val movementRepo       = MovementRepoLive()
  val movementSpecRepo   = MovementSpecRepoLive()
  val reconRepo          = ReconciliationRepoLive()
  val reconSpecRepo      = ReconciliationSpecRepoLive()
  val jobRepo            = JobRepoLive()
  val jobManager = JobManager(
    jobRepo = jobRepo
  )
  val fundFlowOperations = FundFlowOperations(
    fundFlowConfigRepo = fundFlowConfigRepo
  )
  val reconciler = Reconciler(
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    reconSpecRepo      = reconSpecRepo,
    reconRepo          = reconRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )
  val accountOperations = AccountOperations(
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )
  val movementSpecOperations = MovementSpecOperations(
    movementSpecRepo = movementSpecRepo
  )
  val reconSpecOperations = ReconciliationSpecOperations(
    reconSpecRepo = reconSpecRepo
  )

  val ingestor = Ingestor(
    movementRepo = movementRepo,
    accountRepo  = accountRepo
  )

  val prefixPath = "/api/v1"
  val loginPath  = s"$prefixPath / login"

  val randomMessagePath          = s"$prefixPath / random-message"
  val randomMessage2Path         = s"$prefixPath / random-message2"
  val pingPath                   = s"$prefixPath / scala"
  val subscribeServerMessagePath = s"$prefixPath / subscribe"

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

  @cask.route(HttpPath.fundFlows, methods = HttpCmd.GET)
  def getFundFlows() =
    transact(transactor):
      val fundFlows = fundFlowConfigRepo.findAll
      val payloads  = fundFlows.map(ff => Payload.fundFlowPayloadFromDomain(ff))
      write(payloads)

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
      write(accountOperations.createAccount(accountRequest))

  @cask.route(s"${HttpPath.account}/:id", methods = HttpCmd.PUT)
  def updateAccount(id: String, request: cask.Request): String =
    val accountRequest = read[AccountPayload](request.text())
    transact(transactor):
      write(accountOperations.updateAccount(id, accountRequest))

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
      write(movementSpecOperations.createMovementSpec(payload))

  @cask.route(s"${HttpPath.movementSpec}/:id", methods = HttpCmd.PUT)
  def updateMovementSpec(id: String, request: cask.Request): String =
    val payload = read[MovementSpecPayload](request.text())
    transact(transactor):
      write(movementSpecOperations.updateMovementSpec(id, payload))

  @cask.route(s"${HttpPath.movementSpec}/:id", methods = Seq("delete"))
  def deleteMovementSpec(id: String): String =
    transact(transactor):
      movementSpecOperations.deleteMovementSpec(id)
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

  // Jobs
  @cask.route(s"${HttpPath.jobs}/:jobType", methods = HttpCmd.GET)
  def jobsByType(jobType: String): String =
    val jobTypeInfo = jobType match
      case "reconciliation" => JobTypeInfo.Reconciliation
      case "ingestion"      => JobTypeInfo.Ingestion
      case _                => throw IllegalArgumentError("jobType", s"incorrect value $jobType")
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
      write(reconSpecOperations.createReconSpec(payload))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = HttpCmd.PUT)
  def updateReconSpec(id: String, request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    transact(transactor):
      write(reconSpecOperations.updateReconSpec(id, payload))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = Seq("delete"))
  def deleteReconSpec(id: String): String =
    transact(transactor):
      reconSpecOperations.deleteReconSpec(id)
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
