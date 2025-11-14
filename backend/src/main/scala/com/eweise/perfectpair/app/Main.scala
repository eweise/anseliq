package com.eweise.perfectpair.app

import com.augustnagro.magnum.*
import com.eweise.perfectpair.adapter.{AdapterModel, driven}
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, FundFlowConfigRepoLive, MovementRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.config.AppConfig
import com.eweise.perfectpair.domain.model.{AccountType, EqualityMatcher, FundFlowConfig, FundFlowSpec, ID, MovementSpec, RealAccountSpec, ReconciliationAccountSpec}
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache, Reconciler}
import com.eweise.perfectpair.http.DefaultRoutes.*
import com.eweise.perfectpair.model.RandomMessage
import com.eweise.perfectpair.service.JWTIssuer.User
import com.eweise.perfectpair.service.{JWTIssuer, JWTVerifier, NoZioJWTIssuer, NoZioJWTVerifier}
import com.eweise.perfectpair.services.RandomQuotes
import com.typesafe.config.ConfigFactory
import upickle.default.write
import zio.*
import zio.Console.*
import zio.http.*
import upickle.default.*
//import zio.http.codec.HttpCodec.connect
import zio.logging.backend.SLF4J

import java.util.UUID

object Main extends ZIOAppDefault {

  override def run =
    Server
      .serve(public ++ authenticated)
      .provide(
        Server.defaultWithPort(8080),
        JWTVerifier.live,
        JWTIssuer.live,
        AppConfig.live,
        RandomQuotes.live
      )

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

}

@main
def magnum(): Unit =
  val dataSource = driven.DbDataSource.create(ConfigFactory.load())
  val transactor = Transactor(dataSource)

  val users: Vector[ID] = connect(transactor):
    sql"SELECT a.id from recon.account a join recon.account b on a.id = b.id".query[ID].run()

  users.foreach(println)

object Example extends cask.MainRoutes:
  val dataSource = driven.DbDataSource.create(ConfigFactory.load("application.conf"))
  val transactor = Transactor(dataSource)

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

//    val prefix: PathCodec[Unit]                 = Root / "api" / "v1"
  //  val login: PathCodec[Unit]                  = prefix / "login"
  //  val randomMessage: PathCodec[Unit]          = prefix / "random-message"
  //  val randomMessage2: PathCodec[Unit]         = prefix / "random-message2"
  //  val ping: PathCodec[Unit]                   = prefix / "scala"
  //  val subscribeServerMessage: PathCodec[Unit] = prefix / "subscribe"
  val prefixPath                 = "/api/v1"
  val loginPath                  = s"$prefixPath / login"
  val randomMessagePath          = s"$prefixPath / random-message"
  val randomMessage2Path         = s"$prefixPath / random-message2"
  val pingPath                   = s"$prefixPath / scala"
  val subscribeServerMessagePath = s"$prefixPath / subscribe"

//  @cask.get(loginPath)
//  def login(loginpath: String): String = {
//    val loginSuccess = LoginSuccess(NoZioJWTIssuer.issueToken(User("eric")), "not-implemented")
//    upickle.default.write(loginSuccess)
//  }

//        Method.GET / PathDef.randomMessage -> handler(RandomQuotes.getRandomMessage.map(msg => Response.json(RandomMessage(msg).toJson))),
  //      Method.GET / PathDef.randomMessage2 -> handler(RandomQuotes.getRandomMessage.map(msg => Response.json(RandomMessage(msg).toJson)))

  @cask.get(randomMessagePath)
  def getRandomMessage():String = write(RandomMessage("random message"))
  
  @cask.get("/fundflow/:fundFlowName")
  def fundFlow(fundFlowName: String): String =
    val fundFlow = fundFlowCache
      .getConfigByName(fundFlowName)
      .getOrElse(throw Exception(s"fund flow name $fundFlowName not found"))
    fundFlow.fundFlowJson

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

      write(AdapterModel.Account.fromDomainModel(account))

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
        .map(AdapterModel.Movement.fromDomainModel)

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
