package com.eweise.perfectpair.app

import cask.RawDecorator
import cask.model.Request
import cask.model.Response.Raw
import cask.router.Result
import com.augustnagro.magnum.*
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, FundFlowConfigRepoLive, MovementRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.adapter.driven
import com.eweise.perfectpair.adapter.driving.*
import com.eweise.perfectpair.adapter.driving.Payload.accountPayloadfromDomainModel
import com.eweise.perfectpair.common.model.{EqualityMatcher, RandomMessage}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache, Reconciler}
import com.typesafe.config.ConfigFactory
import upickle.default.*
//import zio.http.codec.HttpCodec.connect
import java.util.UUID

object Example extends cask.MainRoutes:
  val dataSource = driven.DbDataSource.create(ConfigFactory.load("application.conf"))
  val transactor = Transactor(dataSource)

  class CORSDecorator extends RawDecorator:
    def wrapFunction(ctx: Request, delegate: Delegate): Result[Raw] =
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

  override def decorators =
    super.decorators ++ Seq(CORSDecorator())

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
    val testFundFlowConfig = createTestData().copy(name = "testconfig")
    try
      accountOperations.createConfig(testFundFlowConfig)
    catch {
      case e: SqlException =>
        println("config already exists")
    }

  transact(transactor):
    fundFlowCache.init()
    val config = fundFlowCache.getConfigByName("testconfig").getOrElse(throw Exception("not found"))
    // create some account data
    val props = Map("id" -> "1")
    accountOperations.postAmount("testconfig", "post_journal", 100d, properties = props)
    accountOperations.postAmount("testconfig", "bank_report", 100d, properties = props)

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

  @cask.options("api/v1/random-message")
  def getRandomMessageOptions() = ""

  @cask.get("api/v1/random-message")
  def getRandomMessage() =
    cask.Response(
      data = write(RandomMessage("random message"))
    )

  @cask.options("api/v1/random-message2")
  def getRandomMessageOptions2() = ""

  @cask.get("api/v1/random-message2")
  def getNextRandomMessage() =
    cask.Response(
      data = write(RandomMessage("next random message"))
    )

  @cask.get("api/v1/fund-flows")
  def getFundFlows() = {
    cask.Response(
      data = write(fundFlowCache.getAll)
    )
  }


  @cask.get("/fundflow/:fundFlowName")
  def fundFlow(fundFlowName: String): String =
    val fundFlow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse(throw Exception(s"fund flow name $fundFlowName not found"))
    fundFlow.fundFlowJson

  @cask.get("/recon-account")
  def reconAccounts(): String =
    transact(transactor):
      val accounts = accountRepo.findAllRecon()
        val payload = accounts.map(Payload.accountPayloadfromDomainModel)
        write(payload)


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

def createTestData(): FundFlowConfig =
  val reconAccountName   = "recon"
  val journalAccountName = "journal"
  val reportAccountName  = "report"

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
      accountSpecs = Seq(reconAccountSpec, journalAccountSpec, reportAccountSpec),
      movementSpecs = Seq(movementSpecPostJournal, movementSpecBankReport)
    )

  FundFlowConfig(name = UUID.randomUUID().toString, fundFlowJson = write(fundFlow, escapeUnicode = true))
