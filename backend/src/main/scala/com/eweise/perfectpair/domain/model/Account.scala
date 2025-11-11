package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*

import java.util
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToUpperSnakeCase)
enum AccountType derives DbCodec:
  case Real, Recon

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
    @Id id: ID = createID(),
    name: String,
    fundFlowId: ID,
    accountType: AccountType,
    var balance: Money = 0
) extends Auditable
    derives DbCodec


object Account:
  val Table = TableInfo[Account, Account, UUID]
