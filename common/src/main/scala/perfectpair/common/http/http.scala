package perfectpair.common.http

import zio.http.codec.PathCodec
import zio.http.codec.PathCodec.*
import zio.http.{Root, Scheme, URL}

object HttpPath:
  val prefix          = "/api/v1"
  val fundFlows       = s"$prefix/fund-flows"
  val fundFlow        = s"$prefix/fund-flow"
  val accounts        = s"$prefix/accounts"
  val account         = s"$prefix/account"
  val movementSpecs   = s"$prefix/movement-specs"
  val movementSpec    = s"$prefix/movement-spec"
  val movements       = s"$prefix/movements"
  val reconSpecs      = s"$prefix/recon-specs"
  val reconSpec       = s"$prefix/recon-spec"
  val reconciliations = s"$prefix/reconciliations"
  val reconciliation  = s"$prefix/reconciliation"
  val disputes        = s"$prefix/disputes"
  val dispute         = s"$prefix/dispute"
  val jobs            = s"$prefix/jobs"
  val events          = s"$prefix/events"

  def url(path: String) = s"http://localhost:8080$path"
// Get rid of this crap
object PathDef {

  /**
   * * Used in Backend project
   */
  val prefix:                 PathCodec[Unit] = Root / "api" / "v1"
  val login:                  PathCodec[Unit] = prefix / "login"
  val randomMessage:          PathCodec[Unit] = prefix / "random-message"
  val randomMessage2:         PathCodec[Unit] = prefix / "random-message2"
  val fundFlows:              PathCodec[Unit] = prefix / "fund-flows"
  val accounts:               PathCodec[Unit] = prefix / "account"
  val ping:                   PathCodec[Unit] = prefix / "scala"
  val subscribeServerMessage: PathCodec[Unit] = prefix / "subscribe"
}

/**
 * * Used in Frontend project
 */
object BackendApiUrl {
  import perfectpair.common.BuildInfo.backendBaseUrl

  val login:                  String = generateUrl(PathDef.login.format(()))
  val randomMessage:          String = generateUrl(PathDef.randomMessage.format(()))
  val randomMessage2:         String = generateUrl(PathDef.randomMessage2.format(()))
  val fundFlows:              String = generateUrl(PathDef.fundFlows.format(()))
  val accounts:               String = generateUrl(PathDef.accounts.format(()))
  val subscribeServerMessage: String = generateWsUrl(PathDef.subscribeServerMessage.format(()))

  private def generateUrl(maybePath: Either[String, zio.http.Path]): String =
    backendBaseUrl + maybePath.toOption.get.toString

  private def generateWsUrl(maybePath: Either[String, zio.http.Path]): String =
    getBackendWebSocketBaseUrl + maybePath.toOption.get.toString

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
}
