package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*
import com.eweise.perfectpair.common.model.model.ID
import upickle.default.*

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FundFlowConfig(
  @Id id: ID = createID(),
  name: String,
  fundFlowJson: String
) extends Auditable
    derives DbCodec, ReadWriter:
  def fundFlow: FundFlowSpec = read[FundFlowSpec](fundFlowJson)
