package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*
import com.eweise.perfectpair.common.model.model.ID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
                           @Id id:      ID     = createID(),
                           specId:      ID,
                           description: String = ""
) extends Auditable
    derives DbCodec
