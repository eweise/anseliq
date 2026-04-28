package perfectpair.adapter.driving

import perfectpair.common.model.{ReconRelationship as ComRel, *}
import perfectpair.domain.model.{Relationship as Rel, *}
import upickle.default.read as upickleRead

import java.util.UUID

object Payload:

  def jobPayloadToDomain(payload: JobInfoPayload): Job =
    Job(
      referenceId  = UUID.fromString(payload.referenceId),
      jobType      = JobType.valueOf(payload.jobType.toString),
      jobStatus    = JobStatus.valueOf(JobInfoStatus.toString),
      jobInfoJson  = Some(upickle.write(payload.jobInfo)),
      errorMessage = payload.errorMessage
    )

  def jobPayloadFromDomain(job: Job): JobInfoPayload =
    JobInfoPayload(
      id           = Some(job.id.toString),
      referenceId  = job.referenceId.toString,
      jobType      = JobTypeInfo.valueOf(job.jobType.toString),
      jobStatus    = JobInfoStatus.valueOf(job.jobStatus.toString),
      jobInfoJson  = job.jobInfoJson,
      errorMessage = job.errorMessage,
      createdAt    = job.createdAt,
      updatedAt    = job.updatedAt
    )

  def fundFlowPayloadToDomain(payload: FundFlowPayload): FundFlowConfig =
    FundFlowConfig(
      name = payload.name
    )

  def fundFlowPayloadFromDomain(ff: FundFlowConfig): FundFlowPayload =
    FundFlowPayload(
      id        = Some(ff.id.toString),
      name      = ff.name,
      createdAt = ff.createdAt,
      updatedAt = ff.updatedAt
    )

  def accountPayloadFromDomain(account: Account): AccountPayload =
    AccountPayload(
      fundFlowId = account.fundFlowId.toString,
      id         = Some(account.id.toString),
      name       = account.name,
      properties = account.properties.props,
      balance    = account.balance
    )

  def movementSpecPayloadFromDomain(spec: MovementSpec): MovementSpecPayload =
    MovementSpecPayload(
      id               = Some(spec.id.toString),
      name             = spec.name,
      inAccountId      = spec.inAccountId.toString,
      outAccountId     = spec.outAccountId.toString,
      dataSourceConfig = Option(spec.dataSourceJson).filter(_.nonEmpty).map(s => upickleRead[DataSourceConfig](s))
    )

  def reconRelationshipToDomain(comRel: ComRel): Rel =
    comRel match
      case ComRel.OneToOne   => Rel.OneToOne
      case ComRel.OneToMany  => Rel.OneToMany
      case ComRel.ManyToOne  => Rel.ManyToOne
      case ComRel.ManyToMany => Rel.ManyToMany

  def reconSpecPayloadFromDomain(spec: ReconciliationSpec): ReconciliationSpecPayload =
    ReconciliationSpecPayload(
      id                = Some(spec.id.toString),
      accountId         = Some(spec.accountId.toString),
      description       = spec.description,
      agingLimit        = spec.agingLimit,
      relationship      = reconRelationshipFromDomain(spec.relationship),
      inMovementSpecId  = spec.inMovementSpecId.toString,
      outMovementSpecId = spec.outMovementSpecId.toString,
      rule              = spec.rule()
    )

  def reconRelationshipFromDomain(rel: Rel): ComRel =
    rel match
      case Rel.OneToOne   => ComRel.OneToOne
      case Rel.OneToMany  => ComRel.OneToMany
      case Rel.ManyToOne  => ComRel.ManyToOne
      case Rel.ManyToMany => ComRel.ManyToMany

  def movementResponsefromDomainModel(dmMovement: Movement): MovementPayload =
    MovementPayload(
      id               = dmMovement.id.toString,
      referenceId      = dmMovement.referenceId.toString,
      outAccountId     = dmMovement.outAccountId.toString,
      inAccountId      = dmMovement.inAccountId.toString,
      amount           = dmMovement.amount,
      properties       = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull
    )
