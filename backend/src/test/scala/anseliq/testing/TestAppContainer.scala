package anseliq.testing

import anseliq.adapter.config.AppContainer
import anseliq.adapter.driven.DbDataSource
import com.typesafe.config.{Config, ConfigFactory}

import javax.sql.DataSource

/**
 * The production AppContainer wiring, pointed at the test database: test-application.conf wins,
 * falling back to application.conf for anything it doesn't set (e.g. the match-suggestions.* keys).
 * A suite needing a different service overrides just that one, the same way as any AppContainer:
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

  // One pool for every container in the test run, rather than a pool each holding its own
  // connections open, which across the suites would run past Postgres's max_connections.
  override lazy val dataSource: DataSource = TestAppContainer.sharedPool

object TestAppContainer:
  private lazy val sharedPool: DataSource =
    DbDataSource.pooled(
      ConfigFactory.load("test-application.conf").withFallback(ConfigFactory.load("application.conf"))
    )
