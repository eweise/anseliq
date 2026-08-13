package perfectpair.adapter.driving

import perfectpair.common.model.model.Money
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
      fundFlowId          = account.fundFlowId.toString,
      id                  = Some(account.id.toString),
      name                = account.name,
      isRecon             = account.isRecon,
      balance             = account.balance,
      reconciledBalance   = account.reconciledBalance,
      unreconciledBalance = account.unreconciledBalance,
      properties          = account.properties.props
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

  def reconFromDomain(recon: Reconciliation): ReconciliationPayload =
    ReconciliationPayload(
      id               = recon.id.toString,
      reconSpecId      = recon.specId.toString,
      appliedRules     = recon.appliedRules,
      description      = recon.description,
      status           = recon.status.toString,
      balance          = recon.balance,
      adjustmentReason = recon.adjustmentReason,
      createdAt        = recon.createdAt.get,
      updatedAt        = recon.updatedAt.get
    )

  def eventPayloadFromDomain(state: EventState): EventPayload =
    upickle.read[Event](state.payload) match
      case e: MoneyMovementsIngested =>
        MoneyMovementsIngestedPayload(
          id             = state.id.toString,
          eventType      = state.eventType,
          movementSpecID = e.movementSpecID.toString,
          status         = state.status.toString,
          retryCount     = state.retryCount,
          errorMessage   = state.errorMessage,
          createdAt      = state.createdAt,
          updatedAt      = state.updatedAt
        )
      case e: AccountReconciled =>
        AccountReconciledPayload(
          id           = state.id.toString,
          eventType    = state.eventType,
          accountID    = e.accountID.toString,
          status       = state.status.toString,
          retryCount   = state.retryCount,
          errorMessage = state.errorMessage,
          createdAt    = state.createdAt,
          updatedAt    = state.updatedAt
        )
      case e: NewFileDetected =>
        NewFileDetectedPayload(
          id              = state.id.toString,
          eventType       = state.eventType,
          fileInventoryID = e.fileInventoryID.toString,
          status          = state.status.toString,
          retryCount      = state.retryCount,
          errorMessage    = state.errorMessage,
          createdAt       = state.createdAt,
          updatedAt       = state.updatedAt
        )

  def movementfromDomainModel(dmMovement: Movement): MovementPayload =
    MovementPayload(
      id               = dmMovement.id.toString,
      referenceId      = dmMovement.referenceId.toString,
      outAccountId     = dmMovement.outAccountId.toString,
      inAccountId      = dmMovement.inAccountId.toString,
      amount           = dmMovement.amount,
      properties       = dmMovement.properties.props,
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull
    )

  def disputeTargetToDomain(p: DisputeTargetPayload): DisputeTarget = p match
    case DisputeTargetPayload.Movement       => DisputeTarget.Movement
    case DisputeTargetPayload.Reconciliation => DisputeTarget.Reconciliation

  def disputeTargetFromDomain(d: DisputeTarget): DisputeTargetPayload = d match
    case DisputeTarget.Movement       => DisputeTargetPayload.Movement
    case DisputeTarget.Reconciliation => DisputeTargetPayload.Reconciliation

  def disputeCategoryToDomain(p: DisputeCategoryPayload): DisputeCategory = p match
    case DisputeCategoryPayload.AmountMismatch     => DisputeCategory.AmountMismatch
    case DisputeCategoryPayload.MissingCounterpart => DisputeCategory.MissingCounterpart
    case DisputeCategoryPayload.Duplicate          => DisputeCategory.Duplicate
    case DisputeCategoryPayload.TimingDifference   => DisputeCategory.TimingDifference
    case DisputeCategoryPayload.DataQuality        => DisputeCategory.DataQuality

  def disputeCategoryFromDomain(d: DisputeCategory): DisputeCategoryPayload = d match
    case DisputeCategory.AmountMismatch     => DisputeCategoryPayload.AmountMismatch
    case DisputeCategory.MissingCounterpart => DisputeCategoryPayload.MissingCounterpart
    case DisputeCategory.Duplicate          => DisputeCategoryPayload.Duplicate
    case DisputeCategory.TimingDifference   => DisputeCategoryPayload.TimingDifference
    case DisputeCategory.DataQuality        => DisputeCategoryPayload.DataQuality

  def disputeStatusToDomain(p: DisputeStatusPayload): DisputeStatus = p match
    case DisputeStatusPayload.New           => DisputeStatus.New
    case DisputeStatusPayload.Investigating => DisputeStatus.Investigating
    case DisputeStatusPayload.Escalated     => DisputeStatus.Escalated
    case DisputeStatusPayload.Resolved      => DisputeStatus.Resolved

  def disputeStatusFromDomain(d: DisputeStatus): DisputeStatusPayload = d match
    case DisputeStatus.New           => DisputeStatusPayload.New
    case DisputeStatus.Investigating => DisputeStatusPayload.Investigating
    case DisputeStatus.Escalated     => DisputeStatusPayload.Escalated
    case DisputeStatus.Resolved      => DisputeStatusPayload.Resolved

  /** accountId/accountName/referenceLabel must be resolved by the caller (see Routes.scala) since which
    * repos to query depends on dispute.targetType. */
  def disputePayloadFromDomain(
    dispute:        Dispute,
    accountId:      String,
    accountName:    String,
    referenceLabel: String
  ): DisputePayload =
    DisputePayload(
      id             = dispute.id.toString,
      referenceId    = dispute.referenceId.toString,
      targetType     = disputeTargetFromDomain(dispute.targetType),
      category       = disputeCategoryFromDomain(dispute.category),
      detail         = dispute.detail,
      status         = disputeStatusFromDomain(dispute.status),
      resolutionNote = dispute.resolutionNote,
      accountId      = accountId,
      accountName    = accountName,
      referenceLabel = referenceLabel,
      createdAt      = dispute.createdAt.get,
      updatedAt      = dispute.updatedAt.get
    )
