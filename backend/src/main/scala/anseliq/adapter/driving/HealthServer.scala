package anseliq.adapter.driving

import com.sun.net.httpserver.{HttpExchange, HttpServer as JdkHttpServer}
import com.typesafe.scalalogging.StrictLogging

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.sql.DataSource
import scala.util.Using
import scala.util.control.NonFatal

/**
 * The Kubernetes probes, served on their own port so every instance has them whichever services it
 * runs (a worker pod has no HttpServer), and without going through HttpServer's auth.
 *
 *   - /health/live: 200 while the process can serve a request. It checks no dependencies, so a
 *     database outage doesn't get every pod restarted.
 *   - /health/ready: 200 if the database answers, else 503.
 */
class HealthServer(dataSource: DataSource, requestedPort: Int) extends StrictLogging:
  private val DbCheckTimeoutSeconds = 2

  private val server = JdkHttpServer.create(InetSocketAddress(requestedPort), 0)
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
  server.createContext("/health/live", exchange => respond(exchange, 200, "ok"))
  server.createContext(
    "/health/ready",
    exchange =>
      if databaseAnswers() then respond(exchange, 200, "ok")
      else respond(exchange, 503, "database unavailable")
  )

  /** The bound port, which differs from requestedPort when that was 0 (any free port). */
  def port: Int = server.getAddress.getPort

  def start(): Unit =
    server.start()
    logger.info(s"Health checks listening on port $port")

  def stop(): Unit = server.stop(0)

  private def databaseAnswers(): Boolean =
    try Using.resource(dataSource.getConnection)(_.isValid(DbCheckTimeoutSeconds))
    catch
      case NonFatal(ex) =>
        logger.warn("Readiness check failed: database unavailable", ex)
        false

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.length)
    Using.resource(exchange.getResponseBody)(_.write(bytes))
