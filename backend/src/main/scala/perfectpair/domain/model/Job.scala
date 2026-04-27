package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID
import perfectpair.domain.model.JobStatus.Submitted

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum JobType derives DbCodec:
  case Ingestion, Reconciliation

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum JobStatus derives DbCodec:
  case Submitted, InProgress, Complete, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Job(
  @Id id:        ID                     = createID(),
  jobType:       JobType,
  jobStatus:     JobStatus              = Submitted,
  referenceId:   ID,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Job:
  val Table = TableInfo[Job, Job, UUID]
