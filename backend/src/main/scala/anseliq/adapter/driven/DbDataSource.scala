package anseliq.adapter.driven

import com.typesafe.config.Config
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

// todo use pooled datasource — note that MovementRepoLive.withUnreconciledCursor deliberately
// holds a raw connection open for the life of a whole reconciliation pass (one per side being
// merge-joined), separate from the short-lived borrow/return every transact(...)/connect(...) call
// elsewhere does. If this becomes a real pool, that usage needs to explicitly bypass it — or the
// pool needs enough headroom for those long-held connections — rather than borrowing from it.
object DbDataSource {
  def create(config: Config): DataSource =
    val source = PGSimpleDataSource()
    source.setCurrentSchema(config.getString("db.schema"))
    source.setServerNames(Array(config.getString("db.host")))
    source.setPortNumber(config.getInt("db.port"))
    source.setDatabaseName(config.getString("db.database_name"))
    source.setUser(config.getString("db.user"))
    source.setPassword(config.getString("db.password"))
    source
}
