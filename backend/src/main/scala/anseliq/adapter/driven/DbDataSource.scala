package anseliq.adapter.driven

import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

object DbDataSource:

  /**
   * A HikariCP pool of at most db.max-connections, for every transact(...)/connect(...) call. Each
   * instance holds up to that many, so the replicas' total must stay under Postgres's
   * max_connections.
   */
  def pooled(config: Config): HikariDataSource =
    val pool = HikariConfig()
    pool.setPoolName("anseliq")
    pool.setDataSource(unpooled(config))
    pool.setMaximumPoolSize(config.getInt("db.max-connections"))
    pool.setMinimumIdle(config.getInt("db.min-idle"))
    pool.setConnectionTimeout(config.getDuration("db.connection-timeout").toMillis)
    HikariDataSource(pool)

  /**
   * A new connection per getConnection, really closed on close(). For MovementRepoLive's
   * withUnreconciledCursor, which holds a connection for a whole reconciliation pass and leaves a
   * WITH HOLD cursor on it if its CLOSE never runs (an interrupted pass): from a pool, that cursor,
   * and the temp file it materialized, would outlive the pass on a connection handed to someone
   * else.
   */
  def unpooled(config: Config): DataSource =
    val source = PGSimpleDataSource()
    source.setCurrentSchema(config.getString("db.schema"))
    source.setServerNames(Array(config.getString("db.host")))
    source.setPortNumber(config.getInt("db.port"))
    source.setDatabaseName(config.getString("db.database_name"))
    source.setUser(config.getString("db.user"))
    source.setPassword(config.getString("db.password"))
    source
