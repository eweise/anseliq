package com.eweise.perfectpair.app

import cask.model.Request
import cask.router.Result
import com.augustnagro.magnum.*
import com.eweise.perfectpair.adapter.driven
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, FundFlowConfigRepoLive, MovementRepoLive, MovementSpecRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.adapter.driving.*
import com.eweise.perfectpair.common.http.HttpPath
import com.eweise.perfectpair.common.model.{AccountPayload, EqualityMatcher, FundFlowPayload, RandomMessage}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache, Reconciler}
import com.typesafe.config.ConfigFactory
import upickle.default.*

import java.util.UUID
import scala.util.Try

object HttpCmd:
  def GET  = Seq("get", "options")
  def POST = Seq("post", "options")

object Example extends cask.MainRoutes:
  val dataSource = driven.DbDataSource.create(ConfigFactory.load("application.conf"))
  val transactor = Transactor(dataSource)

  class corsDecorator extends cask.RawDecorator:
    def wrapFunction(ctx: Request, delegate: Delegate) =
      println(s"CORSDecorator called for ${ctx.exchange.getRequestMethod} ${ctx.exchange.getRequestPath}")

      // Automatically handle OPTIONS requests for CORS preflight
      if ctx.exchange.getRequestMethod.toString == "OPTIONS" then
        println("Handling OPTIONS request")
        Result.Success(
          cask.Response(
            data       = "",
            statusCode = 200,
            headers = Seq(
              "access-control-allow-origin"      -> "*",
              "Access-Control-Allow-Methods"     -> "GET, DELETE, POST, OPTIONS, HEAD",
              "Access-Control-Allow-Headers"     -> "*",
              "Access-Control-Allow-Credentials" -> "true",
              "Access-Control-Max-Age"           -> "86400"
            )
          )
        )
      else
        println("Adding CORS headers to response")
        delegate(ctx, Map.empty).map(response =>
          response.copy(headers =
            response.headers ++ Seq(
              "access-control-allow-origin"      -> "*",
              "Access-Control-Allow-Methods"     -> "GET, DELETE, POST, OPTIONS, HEAD",
              "Access-Control-Allow-Headers"     -> "*",
              "Access-Control-Allow-Credentials" -> "true",
              "Access-Control-Max-Age"           -> "86400"
            )
          )
        )

  override def mainDecorators = Seq(corsDecorator())

  val fundFlowConfigRepo = FundFlowConfigRepoLive()
  val fundFlowCache      = FundFlowCache(fundFlowConfigRepo)
  val accountRepo        = AccountRepoLive()
  val movementRepo       = MovementRepoLive()
  val movementSpecRepo   = MovementSpecRepoLive()
  val reconRepo          = ReconciliationRepoLive()

  val reconciler = Reconciler(
    fundFlowCache      = fundFlowCache,
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    reconRepo          = reconRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )

  val accountOperations = AccountOperations(
    fundFlowCache    = fundFlowCache,
    accountRepo      = accountRepo,
    movementRepo     = movementRepo,
    movementSpecRepo = movementSpecRepo,
    configRepo       = fundFlowConfigRepo
  )

  transact(transactor) {
    val testFundFlowConfig = FundFlowConfig(name = "ACH Recon Fund Flow")
    try
      fundFlowConfigRepo.insert(testFundFlowConfig)
    catch {
      case e: SqlException =>
        println("config already exists")
    }

    val testFundFlowConfig2 = FundFlowConfig(name = "Visa Fund Flow")
    try
      fundFlowConfigRepo.insert(testFundFlowConfig2)
    catch {
      case e: SqlException =>
        println("config2 already exists")
    }

    val achAccount = Account(name = "ach", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(achAccount)

    val journalAccount = Account(name = "journal", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(journalAccount)

    val reconAccount = Account(name = "recon", fundFlowId = testFundFlowConfig.id)
    accountRepo.insert(reconAccount)

    val achToRecon = MovementSpec(name = "ach-to-recon", inAccountId = reconAccount.id, outAccountId = achAccount.id)
    movementSpecRepo.insert(achToRecon)

    val reconToJournal = MovementSpec(name = "recon-journal", inAccountId = journalAccount.id, outAccountId = reconAccount.id)
    movementSpecRepo.insert(reconToJournal)

    // create some account data
    val props = Map("id" -> "1")
    accountOperations.postAmount(movementSpec = achToRecon, amount     = 100d, referenceID = createID(), properties = props)
    accountOperations.postAmount(movementSpec = reconToJournal, amount = 100d, referenceID = createID(), properties = props)
  }

  val prefixPath                 = "/api/v1"
  val loginPath                  = s"$prefixPath / login"
  val randomMessagePath          = s"$prefixPath / random-message"
  val randomMessage2Path         = s"$prefixPath / random-message2"
  val pingPath                   = s"$prefixPath / scala"
  val subscribeServerMessagePath = s"$prefixPath / subscribe"

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

  @cask.route(s"${HttpPath.createFundFlow}", methods = HttpCmd.POST)
  def createFundFlow(request: cask.Request): String =
//    val payload  = read[FundFlowPayload](request.text())
//    val fundFlow = Payload.fundFlowPayloadToDomain(payload)
//
//    transact(transactor):
//      fundFlowConfigRepo.insert(fundFlow)
//    write(Payload.fundFlowPayloadFromDomain(fundFlow))
    write("todo")

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
        .map(Payload.movementPayloadfromDomainModel)

      write(adapterMovements.toSeq)

  initialize()
