package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*
import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import com.eweise.perfectpair.domain.model.helper.JSONBProps

import java.util
import java.util.UUID

//@Table(PostgresDbType, SqlNameMapper.CamelToUpperSnakeCase)
//enum AccountType derives DbCodec:
//  case Real, Recon

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
  @Id id:      ID    = createID(),
  name:        String,
  fundFlowId:  ID,
  properties: JSONBProps     = JSONBProps(Map()),
  var balance: Money = 0
) extends Auditable
    derives DbCodec

object Account:
  val Table = TableInfo[Account, Account, UUID]
