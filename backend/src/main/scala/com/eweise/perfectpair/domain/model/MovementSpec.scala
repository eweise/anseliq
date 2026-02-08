package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.{DbCodec, Id, PostgresDbType, SqlNameMapper, Table}
import com.eweise.perfectpair.common.model.model.ID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MovementSpec(
  @Id id:       ID = createID(),
  name:         String,
  inAccountId:  ID,
  outAccountId: ID
) extends Auditable
    derives DbCodec
