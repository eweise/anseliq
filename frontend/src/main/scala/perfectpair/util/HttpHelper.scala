package perfectpair.util

import perfectpair.common.BuildInfo
import perfectpair.common.http.{BackendApiUrl, HttpPath}
import perfectpair.common.model.*
import perfectpair.model.Msg
import perfectpair.util.HttpHelper.ResponseDecoders.*
import perfectpair.util.PayloadAction
import perfectpair.util.PayloadAction.{Created, Deleted, Updated}
import perfectpair.view.pages.*
import tyrian.Cmd
import tyrian.http.*
import upickle.default.*
import zio.Task
import zio.interop.catz.*
import zio.json.*

object HttpHelper:

  val fetchServerMessage: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage, greetDecoder)
  val fetchServerMessage2: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage2, greetDecoder)
  val fetchFundFlows: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.fundFlows), fundFlowsDecoder)

  def login(username: String, password: String): Cmd[Task, Msg] = {
    val body    = LoginRequest(username, password)
    val request = Request.post(BackendApiUrl.login, Body.json(body.toJson))
    Http.send(request, loginDecoder)
  }

  def createFundFlow(fundFlow: FundFlowPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(HttpPath.fundFlow), Body.json(write(fundFlow))),
      fundFlowDecoder(Created)
    )

  def updateFundFlow(fundFlow: FundFlowPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(HttpPath.url(s"${HttpPath.fundFlow}/${fundFlow.id.get}"), Body.json(write(fundFlow))),
      fundFlowDecoder(Updated)
    )

  def createAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.post(HttpPath.url(HttpPath.account), Body.json(write(account))), accountDecoder(Created))

  def updateAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(
        HttpPath.url(s"${HttpPath.account}/${account.id.get}"),
        Body.json(write(account))
      ),
      accountDecoder(Updated)
    )

  private def put[A](url: String, body: Body): Request[A] =
    println(s"put url=${url}; body=${body}")
    Request(
      method = Method.Put,
      url    = url,
      body   = body
    )

  def fetchAccountsByFundFlowID(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), accountsDecoder)

  private def fetchServerMessageCmd(
    apiUrl:          String,
    resultToDecoder: Decoder[Msg]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.get(apiUrl), resultToDecoder) |+| PrettyLogger.info("http.send")

  private def sendWithAuthToken[A](
    request:         Request[A],
    resultToMessage: Decoder[Msg]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    PrettyLogger.info("Cmd.Emit") |+| Cmd.Emit(
      Msg.SendHttpRequestWithAccessToken(_ => Http.send(request, resultToMessage))
    )

  def fetchReconSpecsByAccountID(accountID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.account}/$accountID/recon-specs"), reconSpecsDecoder)

  def fetchReconSpecsForAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.account}/${account.id.get}/recon-specs"),
      ResponseDecoders.reconSpecsForAccountDecoder(account)
    )

  def fetchMovementSpecsForReconView(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/movement-specs"),
      reconSpecsMovementSpecsDecoder
    )

  def createMovementSpec(spec: MovementSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(HttpPath.movementSpec), Body.json(write(spec))),
      movementSpecDecoder(Created)
    )

  def updateMovementSpec(spec: MovementSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(HttpPath.url(s"${HttpPath.movementSpec}/${spec.id.get}"), Body.json(write(spec))),
      movementSpecDecoder(Updated)
    )

  def createReconSpec(spec: ReconciliationSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(HttpPath.reconSpec), Body.json(write(spec))),
      reconSpecDecoder(Created)
    )

  def updateReconSpec(spec: ReconciliationSpecPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(HttpPath.url(s"${HttpPath.reconSpec}/${spec.id.get}"), Body.json(write(spec))),
      reconSpecDecoder(Updated)
    )

  def deleteReconSpec(id: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      delete(HttpPath.url(s"${HttpPath.reconSpec}/$id")),
      reconSpecDeletedDecoder
    )

  def deleteMovementSpec(id: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      delete(HttpPath.url(s"${HttpPath.movementSpec}/$id")),
      ResponseDecoders.movementSpecDeletedDecoder(id)
    )

  private def delete[A](url: String): Request[A] =
    Request(
      method = Method.Delete,
      url    = url,
      body   = Body.Empty
    )

  def fetchJobsByType(jobType: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.jobs}/$jobType"), ResponseDecoders.jobsDecoder)

  def fetchUnreconciledMovements(movementSpecId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.movements}/$movementSpecId/unreconciled"),
      ResponseDecoders.unreconciledMovementsDecoder
    )

  def ingestFile(movementSpecId: String, content: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(
        HttpPath.url(s"${HttpPath.movementSpec}/$movementSpecId/ingest"),
        Body.json(content)
      ),
      ResponseDecoders.ingestDecoder(movementSpecId)
    )

  def reconcileAccount(accountId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      Request.post(HttpPath.url(s"${HttpPath.account}/$accountId/reconcile"), Body.Empty),
      ResponseDecoders.reconcileDecoder(accountId)
    )

  def fetchReconciliationsByReconSpecID(reconSpecId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(
      HttpPath.url(s"${HttpPath.reconSpec}/$reconSpecId/reconciliations"),
      ResponseDecoders.reconciliationsDecoder
    )

  def fetchAccountsForFundFlowView(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), ResponseDecoders.fundFlowViewAccountsDecoder)

  def fetchMovementSpecsForFundFlowView(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/movement-specs"), ResponseDecoders.fundFlowViewMovementSpecsDecoder)

  object ResponseDecoders:

    val loginDecoder: Decoder[Msg] =
      given JsonDecoder[Either[LoginSuccess, LoginFailure]] =
        JsonDecoder[LoginSuccess] <+> JsonDecoder[LoginFailure]
      Decoder(
        _.body.fromJson[Either[LoginSuccess, LoginFailure]] match {
          case Right(Left(LoginSuccess(at, rt))) =>
            Msg.LoginMsg(LoginPage.Msg.LoginSuccess(at, rt))
          case Right(Right(LoginFailure(msg))) =>
            Msg.LoginMsg(LoginPage.Msg.LoginFailure(msg))
          case _ =>
            Msg.LoginMsg(LoginPage.Msg.LoginFailure("Failed to parse login response"))
        },
        error =>
          Msg.LoginMsg(
            LoginPage.Msg.LoginFailure(s"Server error. Make sure server is running on ${BuildInfo.backendBaseUrl}")
          )
      )

    val greetDecoder: Decoder[Msg] = Decoder(
      onResponse =>
        onResponse.body.fromJson[RandomMessage] match {
          case Right(RandomMessage(text)) => Msg.HomeMsg(Welcome.Msg.GreetingFromBackend(text))
          case _                          => Msg.Logout
        },
      error => Msg.Error(error.toString())
    )

    val fundFlowsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[Seq[FundFlowPayload]](onResponse.body)
          Msg.FundFlowsMsg(FundFlowsView.Msg.FundFlowsFromBackend(ffPayload))
        ,
        error => Msg.Error(error.toString)
      )

    val accountsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          println("accountsDecoder. response.status = " + onResponse.status)
          val accountsPayload = read[Seq[AccountPayload]](onResponse.body)
          println("accountsDecoder accountPayload = " + accountsPayload)
          Msg.AccountsMsg(AccountsView.Msg.AccountsFromBackend(accountsPayload))
        ,
        error =>
          println("AccountDecoder error " + error)
          Msg.Error(error.toString)
      )

    val accountCreatedDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountPayload = read[AccountPayload](onResponse.body)
          Msg.MaintainAccountMsg(MaintainAccountView.Msg.AccountAction(accountPayload, Created))
        ,
        error => Msg.Error(error.toString)
      )
    val reconSpecsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[ReconciliationSpecPayload]](onResponse.body)
          Msg.ReconSpecsMsg(ReconSpecsView.Msg.ReconSpecsFromBackend(specs))
        ,
        error => Msg.Error(error.toString)
      )

    def reconSpecsForAccountDecoder(account: AccountPayload): Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[ReconciliationSpecPayload]](onResponse.body)
          Msg.FundFlowViewMsg(FundFlowView.Msg.ReconSpecsFetched(account, specs))
        ,
        error => Msg.Error(error.toString)
      )
    val reconSpecsMovementSpecsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[MovementSpecPayload]](onResponse.body)
          Msg.ReconSpecsMsg(ReconSpecsView.Msg.MovementSpecsFromBackend(specs))
        ,
        error => Msg.Error(error.toString)
      )
    val reconSpecDeletedDecoder: Decoder[Msg] =
      Decoder(
        _ => Msg.MaintainReconSpecMsg(MaintainReconSpecView.Msg.ReconSpecAction(ReconciliationSpecPayload(), Deleted)),
        error => Msg.Error(error.toString)
      )

    def movementSpecDeletedDecoder(id: String): Decoder[Msg] =
      Decoder(
        _ => Msg.FundFlowViewMsg(FundFlowView.Msg.MovementSpecDeleted(id)),
        error => Msg.Error(error.toString)
      )
    val jobsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val jobs = read[Seq[JobInfoPayload]](onResponse.body)
          Msg.JobsMsg(JobsView.Msg.JobsFromBackend(jobs))
        ,
        error => Msg.Error(error.toString)
      )
    val unreconciledMovementsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val movements = read[Seq[MovementPayload]](onResponse.body)
          Msg.UnreconciledMovementsMsg(UnreconciledMovementsView.Msg.MovementsFromBackend(movements))
        ,
        error => Msg.Error(error.toString)
      )
    val reconciliationsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val reconciliations = read[Seq[ReconciliationPayload]](onResponse.body)
          Msg.ReconciliationsMsg(ReconciliationsView.Msg.ReconciliationsFromBackend(reconciliations))
        ,
        error => Msg.Error(error.toString)
      )

    def accountDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountPayload = read[AccountPayload](onResponse.body)
          Msg.MaintainAccountMsg(MaintainAccountView.Msg.AccountAction(accountPayload, action))
        ,
        error => Msg.Error(error.toString)
      )

    def movementSpecDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val spec = read[MovementSpecPayload](onResponse.body)
          Msg.MaintainMovementSpecsMsg(MaintainMovementSpecView.Msg.MovementSpecAction(spec, action))
        ,
        error => Msg.Error(error.toString)
      )

    def reconSpecDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val spec = read[ReconciliationSpecPayload](onResponse.body)
          Msg.MaintainReconSpecMsg(MaintainReconSpecView.Msg.ReconSpecAction(spec, action))
        ,
        error => Msg.Error(error.toString)
      )

    def fundFlowDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[FundFlowPayload](onResponse.body)
          Msg.MaintainFundFlowMsg(MaintainFundFlowView.Msg.MaintainFundFlow(ffPayload, action))
        ,
        error => Msg.Error(error.toString)
      )

    def ingestDecoder(movementSpecId: String): Decoder[Msg] =
      Decoder(
        _ => Msg.IngestViewMsg(IngestView.Msg.IngestComplete(movementSpecId)),
        error => Msg.IngestViewMsg(IngestView.Msg.IngestError(movementSpecId, error.toString))
      )

    def reconcileDecoder(accountId: String): Decoder[Msg] =
      Decoder(
        _ => Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, isSuccess = true)),
        error => Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, isSuccess = false))
      )

    val fundFlowViewAccountsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accounts = read[Seq[AccountPayload]](onResponse.body)
          Msg.FundFlowViewMsg(FundFlowView.Msg.AccountsFromBackend(accounts))
        ,
        error => Msg.Error(error.toString)
      )

    val fundFlowViewMovementSpecsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val specs = read[Seq[MovementSpecPayload]](onResponse.body)
          Msg.FundFlowViewMsg(FundFlowView.Msg.MovementSpecsFromBackend(specs))
        ,
        error => Msg.Error(error.toString)
      )
