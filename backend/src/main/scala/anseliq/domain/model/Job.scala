package anseliq.domain.model

import anseliq.common.model.JobInfo
import anseliq.common.model.model.ID
import anseliq.domain.model.JobStatus.Submitted
import com.augustnagro.magnum.*
import ujson.Arr.from

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum JobType derives DbCodec:
  case Ingestion, Reconciliation, GenerateReconStats, CreateFileInventory

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum JobStatus derives DbCodec:
  case Submitted, InProgress, Complete, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Job(
  @Id id:        ID                     = createID(),
  referenceId:   ID,
  jobType:       JobType,
  jobStatus:     JobStatus              = Submitted,
  jobInfoJson:   Option[String]         = None,
  progressJson:  Option[String]         = None,
  errorMessage:  Option[String]         = None,
  lockedUntil:   Option[OffsetDateTime] = None,
  attempts:      Int                    = 0,
  runAt:         Option[OffsetDateTime] = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec:

  def jobInfo(): JobInfo = upickle.read(jobInfoJson)

object Job:
  val Table = TableInfo[Job, Job, UUID]
