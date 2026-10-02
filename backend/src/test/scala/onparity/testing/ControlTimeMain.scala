package onparity.testing

import com.typesafe.config.ConfigFactory
import onparity.app.AppEnv
import onparity.domain.fixture.ControlTimeIngestor
import onparity.domain.service.{ClockServiceLive, Ingestor}
import onparity.testing.TestDisputeService.given

object ControlTimeMain:
  def main(args: Array[String]): Unit =
    val clock = ClockServiceLive()
    clock.addHours(0)
    val appEnv = new AppEnv(config = ConfigFactory.load("test-application.conf"), clock = clock):
      override protected def buildIngestor: Ingestor = ControlTimeIngestor()
    appEnv.run(args)
