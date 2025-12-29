package com.eweise.perfectpair.common.http

import zio.http.{Root, Scheme, URL}
import zio.http.codec.PathCodec
import zio.http.codec.PathCodec.*

object HttpPath:
  val prefix    = "/api/v1"
  val fundFlows = s"$prefix/fund-flows"
  val createFundFlow = s"$prefix/fund-flow"
  val accounts  = s"$prefix/accounts"

  def url(path: String) = s"http://localhost:8080$path"
// Get rid of this crap
object PathDef {

  /**
   * * Used in Backend project
   */
  val prefix: PathCodec[Unit]                 = Root / "api" / "v1"
  val login: PathCodec[Unit]                  = prefix / "login"
  val randomMessage: PathCodec[Unit]          = prefix / "random-message"
  val randomMessage2: PathCodec[Unit]         = prefix / "random-message2"
  val fundFlows: PathCodec[Unit]              = prefix / "fund-flows"
  val accounts: PathCodec[Unit]               = prefix / "account"
  val ping: PathCodec[Unit]                   = prefix / "scala"
  val subscribeServerMessage: PathCodec[Unit] = prefix / "subscribe"
}

/**
 * * Used in Frontend project
 */
object BackendApiUrl {
  import com.eweise.perfectpair.common.BuildInfo.backendBaseUrl

  private def getBackendWebSocketBaseUrl =
    URL
      .decode(backendBaseUrl)
      .map { url =>
        url.scheme match
          case Some(Scheme.HTTP) =>
            backendBaseUrl.replace("http", "ws")
          case Some(Scheme.HTTPS) =>
            backendBaseUrl.replace("https", "wss")
          case _ =>
            "invalid_backendBaseUrl"
      }
      .getOrElse("")

  private def generateUrl(maybePath: Either[String, zio.http.Path]): String =
    backendBaseUrl + maybePath.toOption.get.toString

  private def generateWsUrl(maybePath: Either[String, zio.http.Path]): String =
    getBackendWebSocketBaseUrl + maybePath.toOption.get.toString

  val login: String                  = generateUrl(PathDef.login.format(()))
  val randomMessage: String          = generateUrl(PathDef.randomMessage.format(()))
  val randomMessage2: String         = generateUrl(PathDef.randomMessage2.format(()))
  val fundFlows: String              = generateUrl(PathDef.fundFlows.format(()))
  val accounts: String               = generateUrl(PathDef.accounts.format(()))
  val subscribeServerMessage: String = generateWsUrl(PathDef.subscribeServerMessage.format(()))
}
