package com.eweise.perfectpair.util

import com.eweise.perfectpair.common.BuildInfo
import com.eweise.perfectpair.common.http.{BackendApiUrl, HttpPath}
import com.eweise.perfectpair.common.model.*
import com.eweise.perfectpair.model.Msg
import com.eweise.perfectpair.util.HttpHelper.ResponseDecoders.*
import com.eweise.perfectpair.util.PrettyLogger
import tyrian.Cmd
import tyrian.http.*
import upickle.default.*
import zio.Task
import zio.interop.catz.*
import zio.json.*

object HttpHelper:

  private def sendWithAuthToken[A, R <: Msg](request: Request[A], resultToMessage: Decoder[R]): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    PrettyLogger.info("Cmd.Emit") |+| Cmd.Emit(
      Msg.SendHttpRequestWithAccessToken(accessToken =>
        Http.send(request.withHeaders(Header("Authorization", s"Bearer $accessToken")), resultToMessage)
      )
    )

  def login(username: String, password: String): Cmd[Task, Msg.LoginSuccess | Msg.LoginFailure] = {
    val body    = LoginRequest(username, password)
    val request = Request.post(BackendApiUrl.login, Body.json(body.toJson))
    Http.send(request, loginDecoder)
  }

  val fetchServerMessage: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessage(BackendApiUrl.randomMessage, greetDecoder)

  val fetchServerMessage2: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessage(BackendApiUrl.randomMessage2, greetDecoder)

  val fetchFundFlows: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessage(HttpPath.url(HttpPath.fundFlows), fundFlowsDecoder)

  def fetchAccountsByFundFlowID(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessage(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), accountDecoder)

  
  val fetchReconAccounts: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessage(HttpPath.url(HttpPath.accounts), accountDecoder)

  private def fetchServerMessage[R <: Msg](
    apiUrl: String,
    resultToDecoder: Decoder[R]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.get(apiUrl), resultToDecoder) |+| PrettyLogger.info("http.send")

  object ResponseDecoders {

    val loginDecoder: Decoder[Msg.LoginSuccess | Msg.LoginFailure] =
      given JsonDecoder[Either[LoginSuccess, LoginFailure]] =
        JsonDecoder[LoginSuccess] <+> JsonDecoder[LoginFailure]
      Decoder(
        _.body.fromJson[Either[LoginSuccess, LoginFailure]] match {
          case Right(Left(LoginSuccess(at, rt))) =>
            Msg.LoginSuccess(at, rt)
          case Right(Right(LoginFailure(msg))) =>
            Msg.LoginFailure(msg)
          case _ =>
            Msg.LoginFailure("Failed to parse login response")
        },
        error => Msg.LoginFailure(s"Server error. Make sure server is running on ${BuildInfo.backendBaseUrl}")
      )

    val greetDecoder: Decoder[Msg.GreetingFromBackend | Msg.Error | Msg.Logout.type] = Decoder(
      onResponse =>
        val tmp = onResponse.body.fromJson[RandomMessage]
        tmp match {
          case Right(RandomMessage(text)) =>
            Msg.GreetingFromBackend(text)
          case _ =>
            Msg.Logout
        }
      ,
      error => Msg.Error(error.toString())
    )

    val fundFlowsDecoder: Decoder[Msg.FundFlowsFromBackend | Msg.Error] =
      Decoder(
        onResponse =>
          val ffPayload = read[Seq[FundFlowPayload]](onResponse.body)
          Msg.FundFlowsFromBackend(fundFlows = ffPayload)
        ,
        error => com.eweise.perfectpair.model.Msg.Error(error.toString)
      )

    val accountDecoder: Decoder[Msg.ReconAccountsFromBackend | Msg.Error] =
      Decoder(
        onResponse =>
          val accountsPayload = read[Seq[AccountPayload]](onResponse.body)
          Msg.ReconAccountsFromBackend(accounts = accountsPayload)
        ,
        error => com.eweise.perfectpair.model.Msg.Error(error.toString)
      )

  }
