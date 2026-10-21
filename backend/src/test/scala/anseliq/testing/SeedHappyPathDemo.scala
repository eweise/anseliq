package anseliq.testing

import com.typesafe.scalalogging.StrictLogging

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.time.Duration
import scala.util.Try

// Seeds the dev DB with HappyPathScenario's last HappyPathScenario.Days days of history, ending
// today, and leaves it there for demos: the dashboard, reports, jobs and events pages all show it,
// and the manual match page has two pending match suggestions. Each run adds a new
// "Happy Path <id>" fund flow. Stop the backend first: its workers would race this for the
// scenario's jobs. Run explicitly (build.sbt keeps it out of `sbt backend/test`):
//   make seed-demo      suggestions from ReferenceFormatSuggester
//   make seed-demo-llm  suggestions from the local model (DEMO_SUGGESTER=local-llm), e.g. Ollama
class SeedHappyPathDemo extends munit.FunSuite with StrictLogging:
  test("seed the happy path demo data"):
    val useLocalLlm = sys.env.get("DEMO_SUGGESTER").contains("local-llm")
    val app         = HappyPathApp(useLocalLlm)
    if useLocalLlm then requireModelServer(app.config.getString("match-suggestions.local-llm.base-url"))
    val scenario = HappyPathScenario(app)
    val s        = scenario.start()
    scenario.runDays(s)()
    logger.info(s"Seeded fund flow '${s.fundFlow.name}' (${s.fundFlow.id}) with suggestions from ${app.matchSuggester.model}")

  // Fails before any data is written, rather than on the last day when the suggestions run.
  private def requireModelServer(baseUrl: String): Unit =
    val reachable = Try(
      HttpClient
        .newHttpClient()
        .send(HttpRequest.newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/models")).timeout(Duration.ofSeconds(5)).build(), BodyHandlers.discarding())
        .statusCode() == 200
    ).getOrElse(false)
    assert(reachable, s"no model server at $baseUrl: start it first (e.g. `ollama serve`, then `ollama pull qwen2.5:7b`)")
