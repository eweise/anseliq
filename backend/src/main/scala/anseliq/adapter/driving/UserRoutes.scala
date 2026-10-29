package anseliq.adapter.driving

import anseliq.adapter.driving.ApiRoutes.requireAdmin
import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class UserRoutes(
  transactor:  Transactor,
  userService: UserService,
  authService: AuthService
) extends ApiRoutes:

  @cask.route(HttpPath.login, methods = HttpCmd.POST)
  def login(request: cask.Request): String =
    val payload = read[LoginRequest](request.text())
    transact(transactor):
      authService.login(payload.email, payload.password) match
        case Right(token) => write(LoginSuccess(accessToken = token, refreshToken = ""))
        case Left(msg)    => throw UnauthorizedError(msg)

  @cask.route(HttpPath.users, methods = HttpCmd.GET)
  def listUsers()(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    transact(transactor):
      write(userService.listUsers().map(PayloadMapper.userPayloadFromDomain))

  // Unlike listUsers above, any authenticated user can call this — it backs the exception assignment
  // picker on the Exceptions page, not user administration. Viewers are excluded: they're read-only
  // by design, so an exception can never be handed to one.
  @cask.route(HttpPath.assignableUsers, methods = HttpCmd.GET)
  def listAssignableUsers()(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      write(
        userService
          .listUsers()
          .filter(u => u.active && u.role != UserRole.Viewer)
          .map(PayloadMapper.userPayloadFromDomain)
      )

  // methods = Seq("post"), not HttpCmd.POST: listUsers (above) already registers "options" at this
  // exact path (HttpCmd.GET includes it too, for CORS preflight) — cask errors on two endpoints
  // sharing the same method+path pair, so a second "options" registration here would collide.
  @cask.route(HttpPath.users, methods = Seq("post"))
  def createUser(request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[CreateUserRequest](request.text())
    transact(transactor):
      val user = userService.createUser(
        payload.email,
        payload.firstName,
        payload.lastName,
        UserRole.valueOf(payload.role),
        payload.password
      )
      write(PayloadMapper.userPayloadFromDomain(user))

  @cask.route(s"${HttpPath.users}/:id", methods = HttpCmd.PUT)
  def updateUser(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[UpdateUserRequest](request.text())
    transact(transactor):
      val user = userService.updateUser(
        UUID.fromString(id),
        payload.firstName,
        payload.lastName,
        UserRole.valueOf(payload.role),
        payload.active
      )
      write(PayloadMapper.userPayloadFromDomain(user))

  @cask.route(s"${HttpPath.users}/:id/reset-password", methods = HttpCmd.POST)
  def resetUserPassword(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[ResetPasswordRequest](request.text())
    transact(transactor):
      userService.resetPassword(UUID.fromString(id), payload.newPassword)
    write("ok")

  initialize()
