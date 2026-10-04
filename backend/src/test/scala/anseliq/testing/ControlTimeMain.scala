package anseliq.testing

import com.typesafe.config.ConfigFactory
import anseliq.app.AppEnv
import anseliq.domain.fixture.ControlTimeIngestor
import anseliq.domain.service.{ClockServiceLive, Ingestor}
import anseliq.testing.TestDisputeService.given

object ControlTimeMain:
  def main(args: Array[String]): Unit =
    val clock = ClockServiceLive()
    clock.addHours(0)
    val appEnv = new AppEnv(config = ConfigFactory.load("test-application.conf"), clock = clock):
      override protected def buildIngestor: Ingestor = ControlTimeIngestor()
    appEnv.run(args)
