package perfectpair.util

import perfectpair.common.BuildInfo
import perfectpair.common.model.*
import HttpHelper.ResponseDecoders.*
import perfectpair.common.http.{BackendApiUrl, HttpPath}
import perfectpair.model.Msg
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, Welcome}
import tyrian.Cmd
import tyrian.http.*
import upickle.default.*
import zio.Task
import zio.interop.catz.*
import zio.json.*

object HttpHelper:

  private def sendWithAuthToken[A](request: Request[A], resultToMessage: Decoder[Msg]): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    PrettyLogger.info("Cmd.Emit") |+| Cmd.Emit(
      Msg.SendHttpRequestWithAccessToken(_ =>
        Http.send(request, resultToMessage)
      )
    )

  def login(username: String, password: String): Cmd[Task, Msg] = {
    val body    = LoginRequest(username, password)
    val request = Request.post(BackendApiUrl.login, Body.json(body.toJson))
    Http.send(request, loginDecoder)
  }

  val fetchServerMessage: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage, greetDecoder)

  val fetchServerMessage2: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage2, greetDecoder)

  val fetchFundFlows: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.fundFlows), fundFlowsDecoder)

  def createFundFlow(fundFlow: FundFlowPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.post(HttpPath.url(HttpPath.createFundFlow), Body.json(write(fundFlow))), fundFlowCreatedDecoder)

  def fetchAccountsByFundFlowID(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), accountDecoder)

  val fetchReconAccounts: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.accounts), accountDecoder)

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
        error => Msg.LoginMsg(LoginPage.Msg.LoginFailure(s"Server error. Make sure server is running on ${BuildInfo.backendBaseUrl}"))
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
          Msg.FundFlowsMsg(FundFlowsView.Msg.FundFlowsFromBackend(ffPayload)),
        error => Msg.Error(error.toString)
      )

    val fundFlowCreatedDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[FundFlowPayload](onResponse.body)
          Msg.MaintainFundFlowMsg(MaintainFundFlowView.Msg.FundFlowCreated(ffPayload)),
        error => Msg.Error(error.toString)
      )

    val accountDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountsPayload = read[Seq[AccountPayload]](onResponse.body)
          Msg.AccountsMsg(AccountsView.Msg.ReconAccountsFromBackend(accountsPayload)),
        error => Msg.Error(error.toString)
      )
