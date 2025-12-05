package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.{PostgresDbType, SqlNameMapper, Table}

import java.time.OffsetDateTime

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
class Auditable:
  var createdAt: Option[OffsetDateTime] = None
  var updatedAt: Option[OffsetDateTime] = None
