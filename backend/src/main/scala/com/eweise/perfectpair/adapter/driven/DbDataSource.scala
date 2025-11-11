package com.eweise.perfectpair.adapter.driven

import com.typesafe.config.Config
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

//todo use pooled datasource
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
