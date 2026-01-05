package com.eweise.perfectpair.app

import cask.model.Request
import cask.router.Result
import com.augustnagro.magnum.*
import com.eweise.perfectpair.adapter.driven
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, FundFlowConfigRepoLive, MovementRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.adapter.driving.*
import com.eweise.perfectpair.common.http.HttpPath
import com.eweise.perfectpair.common.model.{AccountPayload, EqualityMatcher, FundFlowPayload, RandomMessage}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache, Reconciler}
import com.typesafe.config.ConfigFactory
import upickle.default.*

import java.util.UUID

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
            data = "",
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
  val reconRepo          = ReconciliationRepoLive()

  val reconciler = Reconciler(
    fundFlowCache = fundFlowCache,
    accountRepo = accountRepo,
    movementRepo = movementRepo,
    reconRepo = reconRepo,
    fundFlowConfigRepo = fundFlowConfigRepo
  )

  val accountOperations = AccountOperations(
    fundFlowCache = fundFlowCache,
    accountRepo = accountRepo,
    movementRepo = movementRepo,
    configRepo = fundFlowConfigRepo
  )

  transact(transactor):
    val testFundFlowConfig = createTestData(
      reconName = "ACH Recon",
      journalName = "Journal",
      reportName = "ACH Report"
    ).copy(name = "ACH Recon Fund Flow")
    try
      accountOperations.createConfig(testFundFlowConfig)
    catch {
      case e: SqlException =>
        println("config already exists")
    }

    val testFundFlowConfig2 = createTestData(
      reconName = "Visa Recon",
      journalName = "Journal",
      reportName = "Visa Report"
    ).copy(name = "Visa Fund Flow")
    try
      accountOperations.createConfig(testFundFlowConfig2)
    catch {
      case e: SqlException =>
        println("config2 already exists")
    }

  transact(transactor):
    fundFlowCache.init()
    val config = fundFlowCache.getConfigByName("ACH Recon Fund Flow").getOrElse(throw Exception("not found"))
    // create some account data
    val props = Map("id" -> "1")
    accountOperations.postAmount("ACH Recon Fund Flow", "post_journal", 100d, properties = props)
    accountOperations.postAmount("ACH Recon Fund Flow", "bank_report", 100d, properties = props)

    accountRepo
      .findByFundFlow(config.id)
      .foreach: account =>
        if account.accountType == AccountType.Recon then reconciler.reconcileAccount(account)

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
      val payloads  = fundFlows.map(ff => Payload.fundFlowPayloadFromDomain(ff, accountRepo.findByFundFlow(ff.id))).toSeq
      write(payloads)

  @cask.route(s"${HttpPath.fundFlows}/:fundFlowID/accounts", methods = HttpCmd.GET)
  def accountsByFundFlowID(fundFlowID: String): String =
    transact(transactor):
      val accounts = accountRepo.findByFundFlow(UUID.fromString(fundFlowID))
      val payload  = accounts.map(Payload.accountPayloadfromDomainModel)
      write(payload)

  @cask.route(HttpPath.accounts, methods = HttpCmd.GET)
  def allAccounts(): String =
    transact(transactor):
      val accounts = accountRepo.findAll()
      val payload  = accounts.map(Payload.accountPayloadfromDomainModel)
      write(payload)

  @cask.get("/fundflow/:fundFlowName")
  def fundFlow(fundFlowName: String): String =
    val fundFlow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse(throw Exception(s"fund flow name $fundFlowName not found"))
    fundFlow.fundFlowJson

  @cask.route(s"${HttpPath.createFundFlow}", methods = HttpCmd.POST)
  def createFundFlow(request: cask.Request): String =
    val payload = read[FundFlowPayload](request.text())
    val fundFlow = Payload.fundFlowPayloadToDomain(payload)

    transact(transactor):
      fundFlowConfigRepo.insert(fundFlow)
    write(Payload.fundFlowPayloadFromDomain(fundFlow, Seq()))

  @cask.get("/fundflow/:fundFlowName/account/:accountName")
  def account(fundFlowName: String, accountName: String): String =
    val fundFlow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw Exception(s"fund flow name $fundFlowName not found")

    transact(transactor):
      val account = accountRepo
        .findByFundFlowAndAccountName(fundFlow.id, accountName)
        .getOrElse:
          throw Exception("not found") // todo need proper http errors

      write(Payload.accountPayloadfromDomainModel(account))

  // todo this should just pass in an account ID
  @cask.get("/fundflow/:fundFlowName/account/:accountName/movements")
  def movements(fundFlowName: String, accountName: String): String =
    val fundFlow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse:
        throw Exception(s"fund flow name $fundFlowName not found")

    transact(transactor):
      val account = accountRepo
        .findByFundFlowAndAccountName(fundFlow.id, accountName)
        .getOrElse:
          throw Exception("not found") // todo need proper http errors

      val adapterMovements = movementRepo
        .findByAccount(account.id)
        .map(Payload.movementPayloadfromDomainModel)

      write(adapterMovements)

  initialize()

def createTestData(
  reconName: String,
  journalName: String,
  reportName: String
): FundFlowConfig =
  val reconAccountName   = reconName
  val journalAccountName = journalName
  val reportAccountName  = reportName

  val reconAccountSpec = ReconciliationAccountSpec(
    name = reconAccountName,
    matcher = EqualityMatcher(inKeys = Array("id"), outKeys = Array("id"))
  )
  val journalAccountSpec = RealAccountSpec(name = journalAccountName)
  val reportAccountSpec  = RealAccountSpec(name = reportAccountName)

  val postJournalMovementName = "post_journal"
  val movementSpecPostJournal = MovementSpec(
    name = postJournalMovementName,
    inAccountName = journalAccountName,
    outAccountName = reconAccountName
  )

  val bankReportMovementName = "bank_report"
  val movementSpecBankReport = MovementSpec(
    name = bankReportMovementName,
    inAccountName = reconAccountName,
    outAccountName = reportAccountName
  )

  val fundFlow =
    FundFlowSpec(
      realAccountSpecs = Seq(journalAccountSpec, reportAccountSpec),
      reconAccountSpecs = Seq(reconAccountSpec),
      movementSpecs = Seq(movementSpecPostJournal, movementSpecBankReport)
    )

  FundFlowConfig(name = UUID.randomUUID().toString, fundFlowJson = write(fundFlow, escapeUnicode = true))
