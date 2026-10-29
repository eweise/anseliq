package anseliq.adapter.driving

import anseliq.adapter.driving.ApiRoutes.requireAdmin
import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class ReconciliationRoutes(
  transactor:           Transactor,
  views:                ViewAssembler,
  reconRepo:            ReconciliationRepo,
  reconciliationRunner: ReconciliationRunner,
  manualReconciler:     ManualReconciler
) extends ApiRoutes:

  @cask.route(s"${HttpPath.reconSpec}/:reconSpecID/reconciliations", methods = HttpCmd.GET)
  def movementsByReconciliationSpec(reconSpecID: String): String =
    transact(transactor):
      val reconciliations = reconRepo.findByReconSpecID(UUID.fromString(reconSpecID))
      val payload         = reconciliations.map(PayloadMapper.reconFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.account}/:id/reconciliations", methods = HttpCmd.GET)
  def reconciliationsByAccount(id: String, limit: Int = 100, status: Option[String] = None): String =
    val statusFilter = status.map(s =>
      ReconciliationStatus.values
        .find(_.toString == s)
        .getOrElse(throw IllegalArgumentError("status", s"unknown reconciliation status: $s"))
    )
    transact(transactor):
      val reconciliations = reconRepo.findByAccountID(UUID.fromString(id), limit, statusFilter)
      write(reconciliations.map(PayloadMapper.reconFromDomain))

  // Keyed by ReconciliationStatus name ("Mismatched", "InProgress", ...), statuses with none omitted.
  @cask.route(s"${HttpPath.account}/:id/reconciliation-counts", methods = HttpCmd.GET)
  def reconciliationCountsByAccount(id: String): String =
    transact(transactor):
      write(reconRepo.countByStatusForAccountID(UUID.fromString(id)).map((s, n) => s.toString -> n))

  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reconcileAccount(id: String): String =
    reconciliationRunner.reconcileNow(UUID.fromString(id))
    write("reconciled")

  @cask.route(s"${HttpPath.reconSpec}/:id/manual-match", methods = HttpCmd.POST)
  def manualMatch(id: String, request: cask.Request): String =
    val payload = read[ManualMatchRequest](request.text())
    transact(transactor):
      val reconciliation = manualReconciler.matchMovements(
        UUID.fromString(id),
        payload.inMovementIds.map(UUID.fromString),
        payload.outMovementIds.map(UUID.fromString),
        payload.description
      )
      write(PayloadMapper.reconFromDomain(reconciliation))

  @cask.route(s"${HttpPath.reconciliation}/:id", methods = HttpCmd.GET)
  def getReconciliationDetail(id: String): String =
    transact(transactor):
      write(views.reconciliationDetail(UUID.fromString(id)))

  @cask.route(s"${HttpPath.reconciliation}/:id/unmatch", methods = HttpCmd.POST)
  def unmatchReconciliation(id: String): String =
    transact(transactor):
      manualReconciler.unmatch(UUID.fromString(id))
    write("voided")

  @cask.route(s"${HttpPath.reconciliation}/:id/write-off", methods = HttpCmd.POST)
  def writeOffReconciliation(id: String, request: cask.Request): String =
    val payload = read[WriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.writeOff(UUID.fromString(id), UUID.fromString(payload.movementId), payload.reason)
    write("written-off")

  @cask.route(s"${HttpPath.reconciliation}/:id/reverse-write-off", methods = HttpCmd.POST)
  def reverseWriteOff(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[ReverseWriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.reverseWriteOff(UUID.fromString(id), payload.reason, Some(UUID.fromString(currentUser.userId)))
    write("write-off-reversed")

  initialize()
