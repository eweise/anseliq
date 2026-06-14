package perfectpair.http

import com.raquo.airstream.core.EventStream
import org.scalajs.dom
import upickle.default.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object ApiClient:

  def get[A: Reader](url: String, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "GET", None, token))

  def post[A: Reader, B: Writer](url: String, body: B, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "POST", Some(write(body)), token))

  def postEmpty[A: Reader](url: String, token: String): EventStream[Either[String, A]] =
    EventStream.fromFuture(fetchJson[A](url, "POST", None, token))

  def postRaw(url: String, body: String, token: String): EventStream[Either[String, Unit]] =
    EventStream.fromFuture(fetchRaw(url, "POST", Some(body), token))

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
    val init = js.Dynamic.literal(
      method  = method,
      headers = makeHeaders(token, body.isDefined),
      body    = body.map(b => b: js.Any).getOrElse(js.undefined)
    ).asInstanceOf[dom.RequestInit]
    dom.fetch(url, init).toFuture
      .flatMap { resp =>
        resp.text().toFuture.map { bodyText =>
          if resp.ok then Right(read[A](bodyText))
          else Left(s"HTTP ${resp.status}: $bodyText")
        }
      }
      .recover { case e => Left(e.getMessage) }

  private def fetchRaw(
    url:    String,
    method: String,
    body:   Option[String],
    token:  String
  ): Future[Either[String, Unit]] =
    val init = js.Dynamic.literal(
      method  = method,
      headers = makeHeaders(token, body.isDefined),
      body    = body.map(b => b: js.Any).getOrElse(js.undefined)
    ).asInstanceOf[dom.RequestInit]
    dom.fetch(url, init).toFuture
      .flatMap { resp =>
        resp.text().toFuture.map { bodyText =>
          if resp.ok then Right(())
          else Left(s"HTTP ${resp.status}: $bodyText")
        }
      }
      .recover { case e => Left(e.getMessage) }
