package anseliq.testing

import com.typesafe.scalalogging.StrictLogging

// Seeds the dev DB with HappyPathScenario's last HappyPathScenario.Days days of history, ending
// today, and leaves it there for demos: the dashboard, reports, jobs and events pages all show it.
// Each run adds a new "Happy Path <id>" fund flow. Stop the backend first: its workers would race
// this for the scenario's jobs. Run explicitly (build.sbt keeps it out of `sbt backend/test`):
//   sbt "backend/testOnly anseliq.testing.SeedHappyPathDemo"
class SeedHappyPathDemo extends munit.FunSuite with StrictLogging:
  test("seed the happy path demo data"):
    val scenario = HappyPathScenario(HappyPathApp())
    val s        = scenario.start()
    scenario.runDays(s)()
    logger.info(s"Seeded fund flow '${s.fundFlow.name}' (${s.fundFlow.id})")
