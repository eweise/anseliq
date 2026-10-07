package anseliq.testing

import anseliq.app.AppEnv
import anseliq.domain.fixture.ControlTimeIngestor
import anseliq.domain.service.Ingestor

object ControlTimeMain:
  def main(args: Array[String]): Unit =
    val app = new TestAppContainer {
      override lazy val ingestor: Ingestor = ControlTimeIngestor(this)
    }
    app.clock.addHours(24)
    AppEnv(app).run(args)
