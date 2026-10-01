package onparity.http

import com.raquo.airstream.core.EventStream
import onparity.common.http.HttpPath
import onparity.routing.Nav
import onparity.state.SessionStore
import org.scalajs.dom
import upickle.default.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object ApiClient:

  // A 401 means the token is missing/invalid/expired — every route requires one now. The one
  // exception is /login itself, which returns 401 for bad credentials: that's a failed login to
  // report on the login page, not an expired session, so it's excluded here.
  // Centralized here rather than in every page: any call anywhere in the app hitting a stale
  // session bounces the user back to /login instead of failing silently or showing a confusing
  // "HTTP 401: Unauthorized" error banner.
  private def handleUnauthorized(status: Int, url: String): Unit =
    if status == 401 && url != HttpPath.url(HttpPath.login) then
      SessionStore.clear()
      Nav.push("/login")

  def get[A: Reader](url: String, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "GET", None, token))

  def post[A: Reader, B: Writer](url: String, body: B, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "POST", Some(write(body)), token))

  def postEmpty[A: Reader](url: String, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "POST", None, token))

  def postRaw(url: String, body: String, token: String): EventStream[Either[String, Unit]] =
    EventStream.fromFuture(fetchRaw(url, "POST", Some(body), token))

  // Like postRaw, but for endpoints that return a real JSON payload instead of a bare success —
  // sends body as-is (unlike post[A,B], which JSON-encodes it via write), so this is for sending
  // something that's already raw text (e.g. an uploaded file's contents) and getting typed JSON back.
  def postRawExpectJson[A: Reader](url: String, body: String, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "POST", Some(body), token))

  def put[A: Reader, B: Writer](url: String, body: B, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "PUT", Some(write(body)), token))

  def delete(url: String, token: String): EventStream[Either[String, Unit]] =
    EventStream.fromFuture(fetchRaw(url, "DELETE", None, token))

  private def makeHeaders(token: String, hasBody: Boolean): dom.Headers =
    val h = new dom.Headers()
    h.append("Authorization", s"Bearer $token")
    if hasBody then h.append("Content-Type", "application/json")
    h

  private def fetchJson[A: Reader](
    url:    String,
    method: String,
    body:   Option[String],
    token:  String
  ): Future[Either[String, A]] =
    val init = js.Dynamic
      .literal(
        method  = method,
        headers = makeHeaders(token, body.isDefined),
        body    = body.map(b => b: js.Any).getOrElse(js.undefined)
      )
      .asInstanceOf[dom.RequestInit]
    dom
      .fetch(url, init)
      .toFuture
      .flatMap { resp =>
        resp.text().toFuture.map { bodyText =>
          if resp.ok then Right(read[A](bodyText))
          else
            handleUnauthorized(resp.status, url)
            Left(s"HTTP ${resp.status}: $bodyText")
        }
      }
      .recover { case e => Left(e.getMessage) }

  private def fetchRaw(
    url:    String,
    method: String,
    body:   Option[String],
    token:  String
  ): Future[Either[String, Unit]] =
    val init = js.Dynamic
      .literal(
        method  = method,
        headers = makeHeaders(token, body.isDefined),
        body    = body.map(b => b: js.Any).getOrElse(js.undefined)
      )
      .asInstanceOf[dom.RequestInit]
    dom
      .fetch(url, init)
      .toFuture
      .flatMap { resp =>
        resp.text().toFuture.map { bodyText =>
          if resp.ok then Right(())
          else
            handleUnauthorized(resp.status, url)
            Left(s"HTTP ${resp.status}: $bodyText")
        }
      }
      .recover { case e => Left(e.getMessage) }
