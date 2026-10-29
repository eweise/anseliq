package anseliq.adapter.driving

import anseliq.common.model.model.ID
import anseliq.common.model.{Direction as ComDirection, ReconRelationship as ComRel, *}
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.model.{Relationship as Rel, *}
import anseliq.domain.service.{BadFilePreview, FixBadFileResult, ParseTestResult}
import upickle.default.{read as upickleRead, write as upickleWrite}

import java.util.UUID

object PayloadMapper:

  def jobPayloadFromDomain(job: Job): JobInfoPayload =
    JobInfoPayload(
      id           = Some(job.id.toString),
      referenceId  = job.referenceId.toString,
      jobType      = JobTypeInfo.valueOf(job.jobType.toString),
      jobStatus    = JobInfoStatus.valueOf(job.jobStatus.toString),
      jobInfoJson  = job.jobInfoJson,
      errorMessage = job.errorMessage,
      attempts     = job.attempts,
      runAt        = job.runAt,
      createdAt    = job.createdAt,
      updatedAt    = job.updatedAt
    )

  def matchSuggestionFromDomain(s: MatchSuggestion): MatchSuggestionPayload =
    MatchSuggestionPayload(
      id                   = s.id.toString,
      reconSpecId          = s.reconSpecId.toString,
      movementId           = s.movementId.toString,
      suggestedMovementIds = s.suggestedMovementIds.map(_.toString),
      confidence           = s.confidence,
      rationale            = s.rationale,
      model                = s.model,
      explanation          = s.explanation.map(_.summary),
      createdAt            = s.createdAt
    )

  def ledgerPayloadToDomain(payload: LedgerPayload): Ledger =
    Ledger(
      name             = payload.name,
      dailyCutoffHours = payload.dailyCutoffHours
    )

  def ledgerPayloadFromDomain(ledger: Ledger): LedgerPayload =
    LedgerPayload(
      id               = Some(ledger.id.toString),
      name             = ledger.name,
      createdAt        = ledger.createdAt,
      updatedAt        = ledger.updatedAt,
      dailyCutoffHours = ledger.dailyCutoffHours
    )

  def accountPayloadToDomain(payload: AccountPayload): Account =
    Account(
      name        = payload.name,
      ledgerId    = UUID.fromString(payload.ledgerId),
      accountType = accountTypeToDomain(payload.accountType),
      properties  = JSONBProps(payload.properties)
    )

  def accountPayloadFromDomain(account: Account): AccountPayload =
    AccountPayload(
      ledgerId            = account.ledgerId.toString,
      id                  = Some(account.id.toString),
      name                = account.name,
      accountType         = accountTypeFromDomain(account.accountType),
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

  def movementSpecPayloadToDomain(payload: MovementSpecPayload): MovementSpec =
    MovementSpec(
      name              = payload.name,
      inAccountId       = UUID.fromString(payload.inAccountId),
      outAccountId      = UUID.fromString(payload.outAccountId),
      fileFeedId        = UUID.fromString(payload.fileFeedId),
      routingFilterJson = routingFilterJson(payload)
    )

  def routingFilterJson(payload: MovementSpecPayload): Option[String] =
    payload.routingFilter.map(upickleWrite(_))

  def movementSpecPayloadFromDomain(spec: MovementSpec): MovementSpecPayload =
    MovementSpecPayload(
      id            = Some(spec.id.toString),
      name          = spec.name,
      inAccountId   = spec.inAccountId.toString,
      outAccountId  = spec.outAccountId.toString,
      fileFeedId    = spec.fileFeedId.toString,
      routingFilter = spec.routingFilter()
    )

  def fileFeedPayloadToDomain(payload: FileFeedPayload): FileFeed =
    FileFeed(
      name                 = payload.name,
      dataSourceJson       = dataSourceJson(payload),
      expectedCadenceHours = payload.expectedCadenceHours
    )

  def dataSourceJson(payload: FileFeedPayload): String =
    payload.dataSourceConfig.map(upickleWrite(_)).getOrElse("")

  def fileFeedPayloadFromDomain(feed: FileFeed): FileFeedPayload =
    FileFeedPayload(
      id                   = Some(feed.id.toString),
      name                 = feed.name,
      dataSourceConfig     = Option(feed.dataSourceJson).filter(_.nonEmpty).map(s => upickleRead[DataSourceConfig](s)),
      expectedCadenceHours = feed.expectedCadenceHours
    )

  def reconRelationshipToDomain(comRel: ComRel): Rel =
    comRel match
      case ComRel.OneToOne   => Rel.OneToOne
      case ComRel.OneToMany  => Rel.OneToMany
      case ComRel.ManyToMany => Rel.ManyToMany

  def reconSpecPayloadToDomain(payload: ReconciliationSpecPayload): ReconciliationSpec =
    ReconciliationSpec(
      accountId =
        UUID.fromString(payload.accountId.getOrElse(throw IllegalArgumentException("Account ID is required"))),
      agingLimit        = payload.agingLimit,
      relationship      = reconRelationshipToDomain(payload.relationship),
      inMovementSpecId  = UUID.fromString(payload.inMovementSpecId),
      outMovementSpecId = UUID.fromString(payload.outMovementSpecId),
      ruleJson          = upickleWrite(payload.rule)
    )

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
    recon:          Reconciliation,
    spec:           Option[(ReconciliationSpec, String)], // (spec, its account's name)
    movements:      Seq[ReconciliationMovementPayload],
    ledger:         Option[Ledger],
    openExceptions: Seq[ExceptionPayload]
  ): ReconciliationDetailPayload =
    ReconciliationDetailPayload(
      reconciliation = reconFromDomain(recon),
      spec = spec.map { (s, accountName) =>
        ReconciliationSpecSummaryPayload(
          id           = s.id.toString,
          agingLimit   = s.agingLimit,
          relationship = reconRelationshipFromDomain(s.relationship),
          ruleJson     = s.ruleJson,
          accountId    = s.accountId.toString,
          accountName  = accountName
        )
      },
      movements      = movements,
      ledger         = ledger.map(ledgerPayloadFromDomain),
      openExceptions = openExceptions
    )

  // reconAccountId: the reconciliation spec's account, None for a spec-less Reversed reconciliation.
  def reconciliationMovementFromDomain(
    m:              Movement,
    reconAccountId: Option[ID],
    accountName:    ID => String
  ): ReconciliationMovementPayload =
    val direction = reconAccountId.map(id => if m.inAccountId == id then ComDirection.IN else ComDirection.OUT)
    val otherAccountId = direction.map:
      case ComDirection.IN  => m.outAccountId
      case ComDirection.OUT => m.inAccountId
    ReconciliationMovementPayload(
      id               = m.id.toString,
      referenceId      = m.referenceId.toString,
      amount           = m.amount,
      direction        = direction,
      otherAccountName = otherAccountId.map(accountName),
      movementType     = m.movementType.map(_.toString),
      adjustmentType   = m.adjustmentType.map(_.toString),
      adjustmentNote   = m.adjustmentNote
    )

  def reconFromDomain(recon: Reconciliation): ReconciliationPayload =
    ReconciliationPayload(
      id               = recon.id.toString,
      reconSpecId      = recon.specId.map(_.toString),
      appliedRules     = recon.appliedRules,
      description      = recon.description,
      status           = recon.status.toString,
      balance          = recon.balance,
      adjustmentReason = recon.adjustmentReason,
      createdAt        = recon.createdAt.get,
      updatedAt        = recon.updatedAt.get
    )

  def badFilePreviewPayloadFromDomain(preview: BadFilePreview): BadFilePreviewPayload =
    BadFilePreviewPayload(
      movementsFound           = preview.movementsFound,
      alreadyReversedCount     = preview.alreadyReversedCount,
      reconciledCount          = preview.reconciledCount,
      eligibleForReversalCount = preview.eligibleForReversalCount
    )

  def fixBadFileResultPayloadFromDomain(result: FixBadFileResult): FixBadFileResultPayload =
    FixBadFileResultPayload(
      reconciliationsVoided = result.unreconcile.reconciliationsVoided,
      movementsReversed     = result.reversal.movementsReversed
    )

  def eventPayloadFromDomain(state: EventState): EventPayload =
    upickle.read[Event](state.payload) match
      case e: MoneyMovementsIngested =>
        MoneyMovementsIngestedPayload(
          id                   = state.id.toString,
          eventType            = state.eventType,
          movementSpecId       = e.movementSpecId.toString,
          movementSpecName     = e.movementSpecName,
          fileName             = e.fileName,
          recordCount          = e.recordCount,
          ingestionTimeSeconds = e.ingestionTimeSeconds,
          lastProcessedLine    = e.lastProcessedLine,
          createdAt            = Some(state.createdAt)
        )
      case e: AccountReconciled =>
        AccountReconciledPayload(
          id        = state.id.toString,
          eventType = state.eventType,
          accountId = e.accountId.toString,
          createdAt = Some(state.createdAt)
        )
      case e: NewFileDetected =>
        NewFileDetectedPayload(
          id              = state.id.toString,
          eventType       = state.eventType,
          fileInventoryId = e.fileInventoryId.toString,
          filePath        = e.filePath,
          fileSource      = e.source,
          createdAt       = Some(state.createdAt)
        )

  def movementPayloadFromDomain(dmMovement: Movement): MovementPayload =
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

  def movementSearchResultPayloadFromDomain(
    m:            Movement,
    accountNames: Map[ID, String],
    movSpecNames: Map[ID, String]
  ): MovementSearchResultPayload =
    MovementSearchResultPayload(
      id               = m.id.toString,
      referenceId      = m.referenceId.toString,
      amount           = m.amount,
      inAccountName    = accountNames.getOrElse(m.inAccountId, m.inAccountId.toString),
      outAccountName   = accountNames.getOrElse(m.outAccountId, m.outAccountId.toString),
      movementSpecName = movSpecNames.getOrElse(m.movementSpecId, m.movementSpecId.toString),
      properties       = m.properties.props,
      metaData         = m.metaData.props,
      reconciled       = m.reconciliationId.nonEmpty,
      createdAt        = m.createdAt.get,
      movementType     = m.movementType.map(_.toString),
      adjustmentType   = m.adjustmentType.map(_.toString),
      adjustmentNote   = m.adjustmentNote
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
      movementSpecName  = movementSpecName,
      movementType      = m.movementType.map(_.toString),
      adjustmentType    = m.adjustmentType.map(_.toString),
      adjustmentNote    = m.adjustmentNote
    )

  def adjustmentTypeToDomain(p: AdjustmentTypePayload): AdjustmentType = p match
    case AdjustmentTypePayload.WriteOff => AdjustmentType.WriteOff

  def accountTypeToDomain(p: AccountTypePayload): AccountType = p match
    case AccountTypePayload.Primary   => AccountType.Primary
    case AccountTypePayload.Secondary => AccountType.Secondary
    case AccountTypePayload.Recon     => AccountType.Recon

  def reconciliationFilterToDomain(p: ReconciliationFilterPayload): ReconciliationFilter = p match
    case ReconciliationFilterPayload.All          => ReconciliationFilter.All
    case ReconciliationFilterPayload.Unreconciled => ReconciliationFilter.Unreconciled
    case ReconciliationFilterPayload.OpenBalance  => ReconciliationFilter.OpenBalance

  def accountTypeFromDomain(a: AccountType): AccountTypePayload = a match
    case AccountType.Primary   => AccountTypePayload.Primary
    case AccountType.Secondary => AccountTypePayload.Secondary
    case AccountType.Recon     => AccountTypePayload.Recon

  def exceptionTargetToDomain(p: ExceptionTargetPayload): ExceptionTarget = p match
    case ExceptionTargetPayload.Movement       => ExceptionTarget.Movement
    case ExceptionTargetPayload.Reconciliation => ExceptionTarget.Reconciliation
    case ExceptionTargetPayload.MovementSpec   => ExceptionTarget.MovementSpec
    case ExceptionTargetPayload.FileFeed       => ExceptionTarget.FileFeed

  def exceptionCategoryToDomain(p: ExceptionCategoryPayload): ExceptionCategory = p match
    case ExceptionCategoryPayload.AmountMismatch       => ExceptionCategory.AmountMismatch
    case ExceptionCategoryPayload.MissingCounterpart   => ExceptionCategory.MissingCounterpart
    case ExceptionCategoryPayload.Duplicate            => ExceptionCategory.Duplicate
    case ExceptionCategoryPayload.TimingDifference     => ExceptionCategory.TimingDifference
    case ExceptionCategoryPayload.DataQuality          => ExceptionCategory.DataQuality
    case ExceptionCategoryPayload.FileMissing          => ExceptionCategory.FileMissing
    case ExceptionCategoryPayload.UnroutedRecord       => ExceptionCategory.UnroutedRecord
    case ExceptionCategoryPayload.ExcessiveParseErrors => ExceptionCategory.ExcessiveParseErrors

  def exceptionStatusToDomain(p: ExceptionStatusPayload): ExceptionStatus = p match
    case ExceptionStatusPayload.New           => ExceptionStatus.New
    case ExceptionStatusPayload.Investigating => ExceptionStatus.Investigating
    case ExceptionStatusPayload.Escalated     => ExceptionStatus.Escalated
    case ExceptionStatusPayload.Resolved      => ExceptionStatus.Resolved

  /**
   * accountId/accountName/referenceLabel must be resolved by the caller (see Routes.scala) since
   * which repos to query depends on exception.targetType. raisedByName/assignedToName are likewise
   * resolved by the caller (via userRepo) rather than looked up here, so this stays a pure mapping
   * function with no repo access of its own — same reasoning as the other three.
   */
  def exceptionPayloadFromDomain(
    exception:      ExceptionCase,
    accountId:      String,
    accountName:    String,
    referenceLabel: String,
    raisedByName:   Option[String] = None,
    assignedToName: Option[String] = None
  ): ExceptionPayload =
    ExceptionPayload(
      id                  = exception.id.toString,
      referenceId         = exception.referenceId.toString,
      targetType          = exceptionTargetFromDomain(exception.targetType),
      category            = exceptionCategoryFromDomain(exception.category),
      detail              = exception.detail,
      status              = exceptionStatusFromDomain(exception.status),
      accountId           = accountId,
      accountName         = accountName,
      referenceLabel      = referenceLabel,
      createdAt           = exception.createdAt.get,
      updatedAt           = exception.updatedAt.get,
      raisedByUserId      = exception.raisedByUserId.map(_.toString),
      raisedByName        = raisedByName,
      assignedToUserId    = exception.assignedToUserId.map(_.toString),
      assignedToName      = assignedToName,
      automaticResolution = exception.category.automaticResolution,
      insteadOfReopening  = exception.category.insteadOfReopening
    )

  def exceptionTargetFromDomain(d: ExceptionTarget): ExceptionTargetPayload = d match
    case ExceptionTarget.Movement       => ExceptionTargetPayload.Movement
    case ExceptionTarget.Reconciliation => ExceptionTargetPayload.Reconciliation
    case ExceptionTarget.MovementSpec   => ExceptionTargetPayload.MovementSpec
    case ExceptionTarget.FileFeed       => ExceptionTargetPayload.FileFeed

  def exceptionCategoryFromDomain(d: ExceptionCategory): ExceptionCategoryPayload = d match
    case ExceptionCategory.AmountMismatch       => ExceptionCategoryPayload.AmountMismatch
    case ExceptionCategory.MissingCounterpart   => ExceptionCategoryPayload.MissingCounterpart
    case ExceptionCategory.Duplicate            => ExceptionCategoryPayload.Duplicate
    case ExceptionCategory.TimingDifference     => ExceptionCategoryPayload.TimingDifference
    case ExceptionCategory.DataQuality          => ExceptionCategoryPayload.DataQuality
    case ExceptionCategory.FileMissing          => ExceptionCategoryPayload.FileMissing
    case ExceptionCategory.UnroutedRecord       => ExceptionCategoryPayload.UnroutedRecord
    case ExceptionCategory.ExcessiveParseErrors => ExceptionCategoryPayload.ExcessiveParseErrors

  def exceptionStatusFromDomain(d: ExceptionStatus): ExceptionStatusPayload = d match
    case ExceptionStatus.New           => ExceptionStatusPayload.New
    case ExceptionStatus.Investigating => ExceptionStatusPayload.Investigating
    case ExceptionStatus.Escalated     => ExceptionStatusPayload.Escalated
    case ExceptionStatus.Resolved      => ExceptionStatusPayload.Resolved

  def exceptionCommentPayloadFromDomain(
    c:                 ExceptionComment,
    newAssignedToName: Option[String]
  ): ExceptionCommentPayload =
    ExceptionCommentPayload(
      id                 = c.id.toString,
      body               = c.body,
      newStatus          = c.newStatus.map(exceptionStatusFromDomain),
      newAssignedToName  = newAssignedToName,
      createdAt          = c.createdAt.get,
      relatedExceptionId = c.relatedExceptionId.map(_.toString)
    )

  def reportsSummaryPayloadFromDomain(r: ReportsSummaryReport): ReportsSummaryPayload =
    ReportsSummaryPayload(
      matchRates = r.matchRates.map { m =>
        MatchRatePayload(
          accountId               = m.accountId.toString,
          accountName             = m.accountName,
          balance                 = m.balance,
          reconciledBalance       = m.reconciledBalance,
          balanceCoveragePercent  = m.balanceCoveragePercent,
          reconciledMovementCount = m.reconciledMovementCount,
          totalMovementCount      = m.totalMovementCount,
          matchRatePercent        = m.matchRatePercent
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

  def fileInventoryPayloadFromDomain(fi: FileInventory, fileFeedName: String): FileInventoryPayload =
    FileInventoryPayload(
      id                = fi.id.toString,
      fileFeedId        = fi.fileFeedId.toString,
      fileFeedName      = fileFeedName,
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
          date                      = p.date.toString,
          avgBalanceCoveragePercent = p.avgBalanceCoveragePercent,
          avgMatchRatePercent       = p.avgMatchRatePercent,
          openExceptionCount        = p.openExceptionCount,
          openExceptionsByCategory  = p.openExceptionsByCategory,
          unreconciledBacklogCount  = p.unreconciledBacklogCount,
          overdueCount              = p.overdueCount
        )
      }
    )
