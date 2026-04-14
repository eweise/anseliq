package perfectpair.util

import perfectpair.common.BuildInfo
import perfectpair.common.http.{BackendApiUrl, HttpPath}
import perfectpair.common.model.*
import perfectpair.model.Msg
import perfectpair.util.HttpHelper.ResponseDecoders.*
import perfectpair.util.PayloadAction.{Created, Updated}
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
  val fetchReconAccounts: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.accounts), accountDecoder)

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

  private def put[A](url: String, body: Body): Request[A] =
    Request(
      method = Method.Put,
      url    = url,
      body   = body
    )

  def createAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.post(HttpPath.url(HttpPath.account), Body.json(write(account))), accountDecoder(Created))

  private def sendWithAuthToken[A](
    request:         Request[A],
    resultToMessage: Decoder[Msg]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    PrettyLogger.info("Cmd.Emit") |+| Cmd.Emit(
      Msg.SendHttpRequestWithAccessToken(_ => Http.send(request, resultToMessage))
    )

  def updateAccount(account: AccountPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(
      put(
        HttpPath.url(s"${HttpPath.account}/${account.id.get}"),
        Body.json(write(account))
      ),
      accountDecoder(Updated)
    )

  def fetchAccountsByFundFlowID(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), accountDecoder)

  private def fetchServerMessageCmd(
    apiUrl:          String,
    resultToDecoder: Decoder[Msg]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.get(apiUrl), resultToDecoder) |+| PrettyLogger.info("http.send")

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

    val accountDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountsPayload = read[Seq[AccountPayload]](onResponse.body)
          Msg.AccountsMsg(AccountsView.Msg.ReconAccountsFromBackend(accountsPayload))
        ,
        error => Msg.Error(error.toString)
      )

    val accountCreatedDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountPayload = read[AccountPayload](onResponse.body)
          Msg.MaintainAccountMsg(MaintainAccountView.Msg.AccountAction(accountPayload, Created))
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

    def fundFlowDecoder(action: PayloadAction): Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[FundFlowPayload](onResponse.body)
          Msg.MaintainFundFlowMsg(MaintainFundFlowView.Msg.MaintainFundFlow(ffPayload, action))
        ,
        error => Msg.Error(error.toString)
      )
