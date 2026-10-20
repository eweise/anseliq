package anseliq.testing

import anseliq.adapter.config.AppContainer
import com.typesafe.config.{Config, ConfigFactory}

/**
 * The production AppContainer wiring, pointed at the test database: test-application.conf wins,
 * falling back to application.conf for anything it doesn't set (e.g. the s3.* LocalStack keys). A
 * suite needing a different service overrides just that one, the same way as any AppContainer:
 * {{{
 *   val app = new TestAppContainer {
 *     override lazy val fileSourceFactory = fakeFileSourceFactory
 *   }
 * }}}
 */
class TestAppContainer extends AppContainer:
  /** Settings a test container changes, ahead of test-application.conf. */
  protected def configOverrides: Config = ConfigFactory.empty()

  override lazy val config: Config =
    configOverrides
      .withFallback(ConfigFactory.load("test-application.conf"))
      .withFallback(ConfigFactory.load("application.conf"))
