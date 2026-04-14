package perfectpair.app

import com.augustnagro.magnum.*
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.adapter.driving.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{AccountPayload, FundFlowPayload, RandomMessage}
import perfectpair.domain.model.*
import perfectpair.domain.service.{AccountOperations, FundFlowOperations, Reconciler}
import upickle.default.*

import java.util.UUID
import scala.util.Try

object HttpCmd:
  def GET  = Seq("get", "options")
  def POST = Seq("post", "options")
  def PUT  = Seq("put", "options")

object App extends cask.MainRoutes:
  val dataSource         = DbDataSource.create(ConfigFactory.load("application.conf"))
  val transactor         = Transactor(dataSource)
  val fundFlowConfigRepo = FundFlowConfigRepoLive()
  val accountRepo        = AccountRepoLive()
  val movementRepo       = MovementRepoLive()
  val movementSpecRepo   = MovementSpecRepoLive()
  val reconRepo          = ReconciliationRepoLive()
  val reconSpecRepo      = ReconciliationSpecRepoLive()
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
  val prefixPath = "/api/v1"
  val loginPath  = s"$prefixPath / login"

  transact(transactor) {
    val testFundFlowConfig = FundFlowConfig(name = s"ACH Recon Fund Flow_${createID().toString}")
    try
      fundFlowConfigRepo.insert(testFundFlowConfig)
    catch {
      case e: SqlException =>
        println("config already exists")
    }

    val achAccount = Account(name = "ach", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(achAccount)

    val journalAccount = Account(name = "journal", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(journalAccount)

    val reconAccount = Account(name = "recon", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(reconAccount)

    val achToRecon = MovementSpec(name = "ach-to-recon", inAccountId = reconAccount.id, outAccountId = achAccount.id)
    movementSpecRepo.insert(achToRecon)

    val reconToJournal =
      MovementSpec(name = "recon-journal", inAccountId = journalAccount.id, outAccountId = reconAccount.id)
    movementSpecRepo.insert(reconToJournal)

    // create some account data
    for (i <- 1 to 5) {
      val props = Map("id" -> createID().toString)
      accountOperations.postAmount(
        movementSpec = achToRecon,
        amount       = 100d,
        referenceID  = createID(),
        properties   = props
      )
      accountOperations.postAmount(
        movementSpec = reconToJournal,
        amount       = 100d,
        referenceID  = createID(),
        properties   = props
      )
    }
  }
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
      val account = accountOperations.createAccount(accountRequest)
      write(Payload.accountPayloadFromDomain(account))

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

  // todo this should just pass in an account ID
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
        .map(Payload.movementResponsefromDomainModel)

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
