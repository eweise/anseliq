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

class ExceptionRoutes(
  transactor:     Transactor,
  views:          ViewAssembler,
  exceptionRepo:    ExceptionRepo,
  exceptionService: ExceptionService
) extends ApiRoutes:

  // Exceptions
  @cask.route(HttpPath.exception, methods = HttpCmd.POST)
  def raiseException(request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    val payload = read[RaiseExceptionRequest](request.text())
    transact(transactor):
      val exception = exceptionService.raise(
        UUID.fromString(payload.referenceId),
        PayloadMapper.exceptionTargetToDomain(payload.targetType),
        PayloadMapper.exceptionCategoryToDomain(payload.category),
        payload.detail,
        raisedByUserId = Some(UUID.fromString(currentUser.userId))
      )
      write(views.exceptionPayloadWithNames(exception))

  @cask.route(HttpPath.exceptions, methods = HttpCmd.GET)
  def listExceptions(): String =
    transact(transactor):
      write(exceptionRepo.findAll.map(views.exceptionPayloadWithNames))

  @cask.route(s"${HttpPath.exception}/:id", methods = HttpCmd.GET)
  def getException(id: String): String =
    transact(transactor):
      val exception = exceptionRepo.findById(UUID.fromString(id)).ErrIfNotFound("exception", UUID.fromString(id))
      write(views.exceptionDetail(exception))

  @cask.route(s"${HttpPath.exception}/:id/comments", methods = HttpCmd.POST)
  def addExceptionComment(id: String, request: cask.Request): String =
    val payload = read[AddExceptionCommentRequest](request.text())
    transact(transactor):
      exceptionService.addComment(
        UUID.fromString(id),
        payload.body,
        payload.newStatus.map(PayloadMapper.exceptionStatusToDomain),
        payload.newAssignedToUserId.map(UUID.fromString)
      )
      val exception = exceptionRepo.findById(UUID.fromString(id)).ErrIfNotFound("exception", UUID.fromString(id))
      write(views.exceptionDetail(exception))

  // Quick "assign to me" shortcut — goes through the same addComment path as any other assignment
  // (so it's still recorded on the thread and still subject to the Viewer role check), just with no
  // message and the current user as the target.
  @cask.route(s"${HttpPath.exception}/:id/assign", methods = HttpCmd.POST)
  def assignExceptionToMe(id: String)(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      exceptionService.addComment(
        UUID.fromString(id),
        body                = None,
        newStatus           = None,
        newAssignedToUserId = Some(UUID.fromString(currentUser.userId))
      )
      val exception = exceptionRepo.findById(UUID.fromString(id)).ErrIfNotFound("exception", UUID.fromString(id))
      write(views.exceptionDetail(exception))

  initialize()
