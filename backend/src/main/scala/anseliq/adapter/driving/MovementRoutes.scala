package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID
import scala.util.Try

class MovementRoutes(
  transactor:         Transactor,
  views:              ViewAssembler,
  fundFlowConfigRepo: FundFlowConfigRepo,
  accountRepo:        AccountRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  movementSpecRepo:   MovementSpecRepo,
  disputeRepo:        DisputeRepo,
  manualReconciler:   ManualReconciler
) extends ApiRoutes:

  @cask.route(s"${HttpPath.movements}/:movementSpecID/unreconciled", methods = HttpCmd.GET)
  def movementsBySpecID(movementSpecID: String): String =
    val specID = UUID.fromString(movementSpecID)
    transact(transactor):
      val movements = movementRepo.findUnReconciledByMovementSpec(specID)
      val payload   = movements.map(Payload.movementfromDomainModel)
      write(payload)

  @cask.route(s"${HttpPath.movements}/search", methods = HttpCmd.POST)
  def searchMovements(request: cask.Request): String =
    val req = read[MovementSearchRequest](request.text())
    transact(transactor):
      val criteria = MovementSearchCriteria(
        fundFlowId       = UUID.fromString(req.fundFlowId),
        accountId        = req.accountId.map(UUID.fromString),
        amount           = req.amount,
        movementSpecId   = req.movementSpecId.map(UUID.fromString),
        properties       = req.properties,
        fileSourceQuery  = req.fileSourceQuery,
        reconciliation   = Payload.reconciliationFilterToDomain(req.reconciliation),
        createdFrom      = req.createdFrom,
        createdTo        = req.createdTo,
        movementType     = req.movementType.map(MovementType.valueOf),
        adjustmentType   = req.adjustmentType.map(Payload.adjustmentTypeToDomain)
      )
      val results = movementRepo.search(criteria)
      // Names resolved via the fund flow's full account/movement-spec lists (both already
      // fund-flow-scoped, cheap fetches) rather than accountRepo.findAllById/
      // movementSpecRepo.findAllById on just the ids referenced by results — Magnum's default
      // findAllById binds a Postgres array parameter via JDBC's createArrayOf, which throws
      // ("Unable to find server array type for provided name OTHER") for a UUID id column against
      // this pgjdbc version. Fetching the whole fund flow's accounts/specs sidesteps that
      // entirely and is simpler than working around the array-binding bug.
      val accountNames = accountRepo.findByFundFlow(criteria.fundFlowId).map(a => a.id -> a.name).toMap
      val movSpecNames = movementSpecRepo.findByFundFlowId(criteria.fundFlowId).map(s => s.id -> s.name).toMap
      write(results.map(m => Payload.movementSearchResultPayloadFromDomain(m, accountNames, movSpecNames)))

  @cask.route(s"${HttpPath.movement}/:id", methods = HttpCmd.GET)
  def getMovementDetail(id: String): String =
    transact(transactor):
      val movementId   = UUID.fromString(id)
      val m            = movementRepo.findById(movementId).ErrIfNotFound("movement", movementId)
      val outAccount   = accountRepo.findById(m.outAccountId).ErrIfNotFound("account", m.outAccountId)
      val inAccount    = accountRepo.findById(m.inAccountId).ErrIfNotFound("account", m.inAccountId)
      val movementSpec = movementSpecRepo.findById(m.movementSpecId).ErrIfNotFound("movement spec", m.movementSpecId)
      val detail       = Payload.movementDetailPayloadFromDomain(m, outAccount.name, inAccount.name, movementSpec.name)
      write(
        detail.copy(
          reconciliation = m.reconciliationId.flatMap(reconRepo.findById).map(Payload.reconFromDomain),
          fundFlow       = fundFlowConfigRepo.findById(inAccount.fundFlowId).map(Payload.fundFlowPayloadFromDomain),
          openDisputes   = disputeRepo.findOpenByReferenceId(m.id).map(views.disputePayloadWithNames)
        )
      )

  @cask.route(s"${HttpPath.movements}/bad-file-preview", methods = HttpCmd.POST)
  def previewBadFile(request: cask.Request): String =
    val payload = read[BadFilePreviewRequest](request.text())
    transact(transactor):
      write(Payload.badFilePreviewPayloadFromDomain(manualReconciler.previewBadFile(payload.fileSourceEntry)))

  // Not wrapped in transact(...): fixBadFile manages its own batched transactions internally (same
  // reason ReconciliationRoutes.reoncileAccount calls reconciler.reconcileAccountResumable bare) — a very dirty
  // file shouldn't need one unbounded transaction to fix.
  @cask.route(s"${HttpPath.movements}/bad-file-fix", methods = HttpCmd.POST)
  def fixBadFile(request: cask.Request): String =
    val payload = read[FixBadFileRequest](request.text())
    val result  = manualReconciler.fixBadFile(payload.fileSourceEntry)
    write(Payload.fixBadFileResultPayloadFromDomain(result))

  @cask.get("/account/:id/movements")
  def movements(fundFlowName: String, accountIdStr: String): String =
    val accountID = Try {
      UUID.fromString(accountIdStr)
    }.getOrElse(
      throw new Exception("bad uuid")
    )

    transact(transactor):
      val adapterMovements = movementRepo
        .findByAccount(accountID)
        .map(Payload.movementfromDomainModel)

      write(adapterMovements.toSeq)

  initialize()
