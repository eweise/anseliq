package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.{DbCodec, Id, PostgresDbType, SqlNameMapper, Table}
import com.eweise.perfectpair.common.model.model.ID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ReconciliationSpec(
  @Id id:            ID     = createID(),
  @Id fundFlowId:    ID,
  description:       String = "",
  agingLimit:        Int    = 0,
  matchingRulesJson: String = "{}"
) extends Auditable
    derives DbCodec
