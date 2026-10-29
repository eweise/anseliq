package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID
import scala.util.Try

class MovementRoutes(
  transactor:       Transactor,
  views:            ViewAssembler,
  movementRepo:     MovementRepo,
  manualReconciler: ManualReconciler
) extends ApiRoutes:

  @cask.route(s"${HttpPath.movements}/:movementSpecID/unreconciled", methods = HttpCmd.GET)
  def movementsBySpecID(movementSpecID: String): String =
    val specID = UUID.fromString(movementSpecID)
    transact(transactor):
      val movements = movementRepo.findUnReconciledByMovementSpec(specID)
      val payload   = movements.map(PayloadMapper.movementfromDomainModel)
      write(payload)

  @cask.route(s"${HttpPath.movements}/search", methods = HttpCmd.POST)
  def searchMovements(request: cask.Request): String =
    val req = read[MovementSearchRequest](request.text())
    transact(transactor):
      val criteria = MovementSearchCriteria(
        ledgerId        = UUID.fromString(req.ledgerId),
        accountId       = req.accountId.map(UUID.fromString),
        amount          = req.amount,
        movementSpecId  = req.movementSpecId.map(UUID.fromString),
        properties      = req.properties,
        fileSourceQuery = req.fileSourceQuery,
        reconciliation  = PayloadMapper.reconciliationFilterToDomain(req.reconciliation),
        createdFrom     = req.createdFrom,
        createdTo       = req.createdTo,
        movementType    = req.movementType.map(MovementType.valueOf),
        adjustmentType  = req.adjustmentType.map(PayloadMapper.adjustmentTypeToDomain)
      )
      write(views.movementSearchResults(criteria))

  @cask.route(s"${HttpPath.movement}/:id", methods = HttpCmd.GET)
  def getMovementDetail(id: String): String =
    transact(transactor):
      write(views.movementDetail(UUID.fromString(id)))

  @cask.route(s"${HttpPath.movements}/bad-file-preview", methods = HttpCmd.POST)
  def previewBadFile(request: cask.Request): String =
    val payload = read[BadFilePreviewRequest](request.text())
    transact(transactor):
      write(PayloadMapper.badFilePreviewPayloadFromDomain(manualReconciler.previewBadFile(payload.fileSourceEntry)))

  // Not wrapped in transact(...): fixBadFile manages its own batched transactions internally (the same
  // way ReconciliationRunner runs reconcileAccountResumable) — a very dirty file shouldn't need one
  // unbounded transaction to fix.
  @cask.route(s"${HttpPath.movements}/bad-file-fix", methods = HttpCmd.POST)
  def fixBadFile(request: cask.Request): String =
    val payload = read[FixBadFileRequest](request.text())
    val result  = manualReconciler.fixBadFile(payload.fileSourceEntry)
    write(PayloadMapper.fixBadFileResultPayloadFromDomain(result))

  @cask.get("/account/:id/movements")
  def movements(ledgerName: String, accountIdStr: String): String =
    val accountID = Try {
      UUID.fromString(accountIdStr)
    }.getOrElse(
      throw new Exception("bad uuid")
    )

    transact(transactor):
      val adapterMovements = movementRepo
        .findByAccount(accountID)
        .map(PayloadMapper.movementfromDomainModel)

      write(adapterMovements.toSeq)

  initialize()
