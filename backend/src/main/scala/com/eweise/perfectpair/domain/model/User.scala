package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*

import java.time.OffsetDateTime

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class User(
    @Id id: Long,
    firstName: Option[String],
    lastName: String,
    created: OffsetDateTime
) extends Auditable
    derives DbCodec
