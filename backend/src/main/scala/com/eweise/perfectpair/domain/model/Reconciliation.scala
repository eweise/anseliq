package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id: ID = createID(),
  description: String = ""
) extends Auditable
    derives DbCodec
