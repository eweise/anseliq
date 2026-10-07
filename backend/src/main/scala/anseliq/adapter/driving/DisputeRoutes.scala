package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class DisputeRoutes(
  transactor:     Transactor,
  views:          ViewAssembler,
  disputeRepo:    DisputeRepo,
  disputeService: DisputeService
) extends ApiRoutes:

  // Disputes
  @cask.route(HttpPath.dispute, methods = HttpCmd.POST)
  def raiseDispute(request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    val payload = read[RaiseDisputeRequest](request.text())
    transact(transactor):
      val dispute = disputeService.raise(
        UUID.fromString(payload.referenceId),
        PayloadMapper.disputeTargetToDomain(payload.targetType),
        PayloadMapper.disputeCategoryToDomain(payload.category),
        payload.detail,
        raisedByUserId = Some(UUID.fromString(currentUser.userId))
      )
      write(views.disputePayloadWithNames(dispute))

  @cask.route(HttpPath.disputes, methods = HttpCmd.GET)
  def listDisputes(): String =
    transact(transactor):
      write(disputeRepo.findAll.map(views.disputePayloadWithNames))

  @cask.route(s"${HttpPath.dispute}/:id", methods = HttpCmd.GET)
  def getDispute(id: String): String =
    transact(transactor):
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(views.disputeDetail(dispute))

  @cask.route(s"${HttpPath.dispute}/:id/comments", methods = HttpCmd.POST)
  def addDisputeComment(id: String, request: cask.Request): String =
    val payload = read[AddDisputeCommentRequest](request.text())
    transact(transactor):
      disputeService.addComment(
        UUID.fromString(id),
        payload.body,
        payload.newStatus.map(PayloadMapper.disputeStatusToDomain),
        payload.newAssignedToUserId.map(UUID.fromString)
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(views.disputeDetail(dispute))

  // Quick "assign to me" shortcut — goes through the same addComment path as any other assignment
  // (so it's still recorded on the thread and still subject to the Viewer role check), just with no
  // message and the current user as the target.
  @cask.route(s"${HttpPath.dispute}/:id/assign", methods = HttpCmd.POST)
  def assignDisputeToMe(id: String)(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      disputeService.addComment(
        UUID.fromString(id),
        body                = None,
        newStatus           = None,
        newAssignedToUserId = Some(UUID.fromString(currentUser.userId))
      )
      val dispute = disputeRepo.findById(UUID.fromString(id)).ErrIfNotFound("dispute", UUID.fromString(id))
      write(views.disputeDetail(dispute))

  initialize()
