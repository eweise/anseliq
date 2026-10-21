package anseliq.adapter.driven

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import java.sql.Connection
import scala.util.Using

// Each test migrates a scratch database of its own, created and dropped here, rather than the
// shared test database, which is already migrated.
class DbMigrationsSuite extends munit.FunSuite:
  private val baseConfig =
    ConfigFactory.load("test-application.conf").withFallback(ConfigFactory.load("application.conf"))

  private def configFor(database: String): Config =
    baseConfig.withValue("db.database_name", ConfigValueFactory.fromAnyRef(database))

  private def withScratchDatabase[T](body: String => T): T =
    val database = s"migrations_test_${System.nanoTime()}"
    def admin(sql: String): Unit =
      Using.resource(DbDataSource.unpooled(configFor("postgres")).getConnection)(_.createStatement().execute(sql))
    admin(s"create database $database")
    try body(database)
    finally admin(s"drop database if exists $database with (force)")

  private def query[T](database: String, sql: String)(read: java.sql.ResultSet => T): Vector[T] =
    Using.resource(DbDataSource.unpooled(configFor(database)).getConnection): (con: Connection) =>
      val rs = con.createStatement().executeQuery(sql)
      Iterator.continually(rs).takeWhile(_.next()).map(read).toVector

  private def appliedVersions(database: String): Vector[(String, String)] =
    query(database, "select version, type from recon.flyway_schema_history where version is not null order by installed_rank"):
      rs => (rs.getString(1), rs.getString(2))

  test("an empty database gets every migration, the schema and the seeded admin"):
    withScratchDatabase: database =>
      DbMigrations.migrate(configFor(database))
      assertEquals(appliedVersions(database), Vector("1" -> "SQL"))
      assertEquals(query(database, "select email from recon.app_user")(_.getString(1)), Vector("admin@onparity.local"))

  test("a database that predates Flyway is baselined at V1 rather than having V1 run over it"):
    withScratchDatabase: database =>
      // Stands in for a schema.sql-built database: a non-empty recon schema with no history table.
      Using.resource(DbDataSource.unpooled(configFor(database)).getConnection):
        _.createStatement().execute("create schema recon; create table recon.app_user (id uuid primary key)")
      DbMigrations.migrate(configFor(database))
      assertEquals(appliedVersions(database), Vector("1" -> "BASELINE"))

  test("migrating again is a no-op"):
    withScratchDatabase: database =>
      DbMigrations.migrate(configFor(database))
      DbMigrations.migrate(configFor(database))
      assertEquals(appliedVersions(database), Vector("1" -> "SQL"))
