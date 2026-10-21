package anseliq.adapter.driven

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import org.flywaydb.core.Flyway

/**
 * Brings the database schema up to date with the migrations in db/migration on the classpath
 * (backend/src/main/resources/db/migration), recording what it applied in recon's
 * flyway_schema_history table.
 *
 * Safe for several instances to run at once: Flyway holds a Postgres advisory lock while it
 * migrates, so the others wait and then find nothing left to do.
 */
object DbMigrations extends StrictLogging:

  def migrate(config: Config): Unit =
    val flyway = Flyway
      .configure()
      // Unpooled: this runs once, before the pool is wanted, and shouldn't keep connections open.
      .dataSource(DbDataSource.unpooled(config))
      .schemas(config.getString("db.schema"))
      // A database created from the old schema.sql has the V1 schema but no history table: it's
      // recorded as already at V1 instead of having V1 run against tables that exist.
      .baselineOnMigrate(true)
      .baselineVersion("1")
      .load()
    val result = flyway.migrate()
    logger.info(
      s"Database schema at version ${Option(result.targetSchemaVersion).getOrElse(result.initialSchemaVersion)}, " +
        s"${result.migrationsExecuted} migration(s) applied"
    )
