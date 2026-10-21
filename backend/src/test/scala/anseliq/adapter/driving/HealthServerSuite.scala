package anseliq.adapter.driving

import anseliq.testing.TestAppContainer
import org.postgresql.ds.PGSimpleDataSource

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import javax.sql.DataSource

class HealthServerSuite extends munit.FunSuite:
  private val client = HttpClient.newHttpClient()

  // Nothing listens on port 1, so connecting fails straight away.
  private def unreachableDatabase: DataSource =
    val source = PGSimpleDataSource()
    source.setServerNames(Array("localhost"))
    source.setPortNumbers(Array(1))
    source

  private def withServer[T](dataSource: DataSource)(body: HealthServer => T): T =
    val server = HealthServer(dataSource, 0)
    server.start()
    try body(server)
    finally server.stop()

  private def statusOf(server: HealthServer, path: String): Int =
    val request = HttpRequest.newBuilder(URI.create(s"http://localhost:${server.port}$path")).build()
    client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()

  test("live and ready are 200 when the database answers"):
    withServer(TestAppContainer().dataSource): server =>
      assertEquals(statusOf(server, "/health/live"), 200)
      assertEquals(statusOf(server, "/health/ready"), 200)

  test("ready is 503 but live stays 200 when the database is unreachable"):
    withServer(unreachableDatabase): server =>
      assertEquals(statusOf(server, "/health/live"), 200)
      assertEquals(statusOf(server, "/health/ready"), 503)
