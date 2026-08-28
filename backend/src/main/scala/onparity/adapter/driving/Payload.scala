package onparity.adapter.driving

import onparity.common.model.{ReconRelationship as ComRel, *}
import onparity.domain.model.{Relationship as Rel, *}
import onparity.domain.service.ParseTestResult
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
      name             = payload.name,
      dailyCutoffHours = payload.dailyCutoffHours
    )

  def fundFlowPayloadFromDomain(ff: FundFlowConfig): FundFlowPayload =
    FundFlowPayload(
      id               = Some(ff.id.toString),
      name             = ff.name,
      createdAt        = ff.createdAt,
      updatedAt        = ff.updatedAt,
      dailyCutoffHours = ff.dailyCutoffHours
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

  // No passwordHash field — this payload is the one that goes back to the frontend, and a
  // password hash (even hashed) has no legitimate reason to leave the backend.
  def userPayloadFromDomain(user: User): UserPayload =
    UserPayload(
      id        = Some(user.id.toString),
      email     = user.email,
      firstName = user.firstName,
      lastName  = user.lastName,
      role      = user.role.toString,
      active    = user.active
    )

  def movementSpecPayloadFromDomain(spec: MovementSpec): MovementSpecPayload =
    MovementSpecPayload(
      id                   = Some(spec.id.toString),
      name                 = spec.name,
      inAccountId          = spec.inAccountId.toString,
      outAccountId         = spec.outAccountId.toString,
      dataSourceConfig     = Option(spec.dataSourceJson).filter(_.nonEmpty).map(s => upickleRead[DataSourceConfig](s)),
      expectedCadenceHours = spec.expectedCadenceHours
    )

  def reconRelationshipToDomain(comRel: ComRel): Rel =
    comRel match
      case ComRel.OneToOne   => Rel.OneToOne
      case ComRel.OneToMany  => Rel.OneToMany
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
      case Rel.ManyToMany => ComRel.ManyToMany

  def reconciliationDetailPayloadFromDomain(
    recon:       Reconciliation,
    spec:        ReconciliationSpec,
    accountName: String,
    movements:   Seq[Movement]
  ): ReconciliationDetailPayload =
    ReconciliationDetailPayload(
      reconciliation = reconFromDomain(recon),
      spec = ReconciliationSpecSummaryPayload(
        id           = spec.id.toString,
        agingLimit   = spec.agingLimit,
        relationship = reconRelationshipFromDomain(spec.relationship),
        ruleJson     = spec.ruleJson,
        accountName  = accountName
      ),
      movements = movements.map(m => ReconciliationMovementPayload(id = m.id.toString, amount = m.amount))
    )

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
          id                   = state.id.toString,
          eventType            = state.eventType,
          movementSpecID       = e.movementSpecID.toString,
          movementSpecName     = e.movementSpecName,
          fileName             = e.fileName,
          recordCount          = e.recordCount,
          ingestionTimeSeconds = e.ingestionTimeSeconds,
          lastProcessedLine    = e.lastProcessedLine,
          status               = state.status.toString,
          retryCount           = state.retryCount,
          errorMessage         = state.errorMessage,
          createdAt            = state.createdAt,
          updatedAt            = state.updatedAt
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
      reconciliationId = dmMovement.reconciliationId.map(_.toString).orNull,
      createdAt        = dmMovement.createdAt.get
    )

  def parseTestResultPayloadFromDomain(r: ParseTestResult): ParseTestResultPayload =
    ParseTestResultPayload(
      rows           = r.rows.map(row => ParsedRowPayload(row.lineNumber, row.props, row.amount)),
      errors         = r.errors.map(e => ParseErrorPayload(e.lineNumber, e.message)),
      linesProcessed = r.linesProcessed,
      truncated      = r.truncated
    )

  def movementDetailPayloadFromDomain(
    m:                Movement,
    outAccountName:   String,
    inAccountName:    String,
    movementSpecName: String
  ): MovementDetailPayload =
    MovementDetailPayload(
      id                = m.id.toString,
      referenceId       = m.referenceId.toString,
      outAccountName    = outAccountName,
      outAccountBalance = m.outAccountBalance,
      inAccountName     = inAccountName,
      inAccountBalance  = m.inAccountBalance,
      amount            = m.amount,
      properties        = m.properties.props,
      metaData          = m.metaData.props,
      movementSpecName  = movementSpecName
    )

  def disputeTargetToDomain(p: DisputeTargetPayload): DisputeTarget = p match
    case DisputeTargetPayload.Movement       => DisputeTarget.Movement
    case DisputeTargetPayload.Reconciliation => DisputeTarget.Reconciliation
    case DisputeTargetPayload.MovementSpec   => DisputeTarget.MovementSpec

  def disputeCategoryToDomain(p: DisputeCategoryPayload): DisputeCategory = p match
    case DisputeCategoryPayload.AmountMismatch     => DisputeCategory.AmountMismatch
    case DisputeCategoryPayload.MissingCounterpart => DisputeCategory.MissingCounterpart
    case DisputeCategoryPayload.Duplicate          => DisputeCategory.Duplicate
    case DisputeCategoryPayload.TimingDifference   => DisputeCategory.TimingDifference
    case DisputeCategoryPayload.DataQuality        => DisputeCategory.DataQuality
    case DisputeCategoryPayload.FileMissing        => DisputeCategory.FileMissing

  def disputeStatusToDomain(p: DisputeStatusPayload): DisputeStatus = p match
    case DisputeStatusPayload.New           => DisputeStatus.New
    case DisputeStatusPayload.Investigating => DisputeStatus.Investigating
    case DisputeStatusPayload.Escalated     => DisputeStatus.Escalated
    case DisputeStatusPayload.Resolved      => DisputeStatus.Resolved

  /**
   * accountId/accountName/referenceLabel must be resolved by the caller (see Routes.scala) since
   * which repos to query depends on dispute.targetType. raisedByName/assignedToName are likewise
   * resolved by the caller (via userRepo) rather than looked up here, so this stays a pure mapping
   * function with no repo access of its own — same reasoning as the other three.
   */
  def disputePayloadFromDomain(
    dispute:        Dispute,
    accountId:      String,
    accountName:    String,
    referenceLabel: String,
    raisedByName:   Option[String] = None,
    assignedToName: Option[String] = None
  ): DisputePayload =
    DisputePayload(
      id               = dispute.id.toString,
      referenceId      = dispute.referenceId.toString,
      targetType       = disputeTargetFromDomain(dispute.targetType),
      category         = disputeCategoryFromDomain(dispute.category),
      detail           = dispute.detail,
      status           = disputeStatusFromDomain(dispute.status),
      accountId        = accountId,
      accountName      = accountName,
      referenceLabel   = referenceLabel,
      createdAt        = dispute.createdAt.get,
      updatedAt        = dispute.updatedAt.get,
      raisedByUserId   = dispute.raisedByUserId.map(_.toString),
      raisedByName     = raisedByName,
      assignedToUserId = dispute.assignedToUserId.map(_.toString),
      assignedToName   = assignedToName
    )

  def disputeTargetFromDomain(d: DisputeTarget): DisputeTargetPayload = d match
    case DisputeTarget.Movement       => DisputeTargetPayload.Movement
    case DisputeTarget.Reconciliation => DisputeTargetPayload.Reconciliation
    case DisputeTarget.MovementSpec   => DisputeTargetPayload.MovementSpec

  def disputeCategoryFromDomain(d: DisputeCategory): DisputeCategoryPayload = d match
    case DisputeCategory.AmountMismatch     => DisputeCategoryPayload.AmountMismatch
    case DisputeCategory.MissingCounterpart => DisputeCategoryPayload.MissingCounterpart
    case DisputeCategory.Duplicate          => DisputeCategoryPayload.Duplicate
    case DisputeCategory.TimingDifference   => DisputeCategoryPayload.TimingDifference
    case DisputeCategory.DataQuality        => DisputeCategoryPayload.DataQuality
    case DisputeCategory.FileMissing        => DisputeCategoryPayload.FileMissing

  def disputeStatusFromDomain(d: DisputeStatus): DisputeStatusPayload = d match
    case DisputeStatus.New           => DisputeStatusPayload.New
    case DisputeStatus.Investigating => DisputeStatusPayload.Investigating
    case DisputeStatus.Escalated     => DisputeStatusPayload.Escalated
    case DisputeStatus.Resolved      => DisputeStatusPayload.Resolved

  def disputeCommentPayloadFromDomain(c: DisputeComment): DisputeCommentPayload =
    DisputeCommentPayload(
      id        = c.id.toString,
      body      = c.body,
      newStatus = c.newStatus.map(disputeStatusFromDomain),
      createdAt = c.createdAt.get
    )

  def reportsSummaryPayloadFromDomain(r: ReportsSummaryReport): ReportsSummaryPayload =
    ReportsSummaryPayload(
      matchRates = r.matchRates.map { m =>
        MatchRatePayload(
          accountId         = m.accountId.toString,
          accountName       = m.accountName,
          balance           = m.balance,
          reconciledBalance = m.reconciledBalance,
          matchRatePercent  = m.matchRatePercent
        )
      },
      exceptions = ExceptionBreakdownPayload(byCategory = r.exceptions.byCategory, byStatus = r.exceptions.byStatus),
      aging      = AgingBucketsPayload(buckets = r.aging.buckets, overdueCount = r.aging.overdueCount)
    )

  def unreconciledMovementPayloadFromDomain(m: UnreconciledMovementDetail): UnreconciledMovementPayload =
    UnreconciledMovementPayload(
      movementId  = m.movementId.toString,
      referenceId = m.referenceId.toString,
      amount      = m.amount,
      accountId   = m.accountId.toString,
      accountName = m.accountName,
      ageDays     = m.ageDays,
      agingBucket = m.agingBucket,
      createdAt   = m.createdAt
    )

  def fileInventoryPayloadFromDomain(fi: FileInventory, movementSpecName: String): FileInventoryPayload =
    FileInventoryPayload(
      id                = fi.id.toString,
      movementSpecId    = fi.movementSpecId.toString,
      movementSpecName  = movementSpecName,
      sourceSystem      = fi.sourceSystem,
      fileName          = fi.fileSourceEntry().name,
      fileSizeBytes     = fi.fileSizeBytes,
      lastModifiedAt    = fi.lastModifiedAt,
      status            = fi.status.toString,
      lastProcessedAt   = fi.lastProcessedAt,
      lastProcessedLine = fi.lastProcessedLine,
      updatedAt         = fi.updatedAt
    )

  def trendReportPayloadFromDomain(r: TrendReport): TrendReportPayload =
    TrendReportPayload(
      points = r.points.map { p =>
        TrendPointPayload(
          date                     = p.date.toString,
          avgMatchRatePercent      = p.avgMatchRatePercent,
          openExceptionCount       = p.openExceptionCount,
          openExceptionsByCategory = p.openExceptionsByCategory,
          unreconciledBacklogCount = p.unreconciledBacklogCount,
          overdueCount             = p.overdueCount
        )
      }
    )
