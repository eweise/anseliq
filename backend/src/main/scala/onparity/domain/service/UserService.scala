package onparity.domain.service

import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.port.driven.{Txn, UserRepo}

// Deliberately does not check the caller's role/permission itself — matches every other service
// in this app (e.g. DisputeService), where authorization is the route's responsibility, not the
// service's. The route layer checks currentUser.role before calling into these Admin-only actions.
class UserService(using userRepo: UserRepo, clock: ClockService):

  private val minPasswordLength = 8

  def listUsers(): Txn[Vector[User]] = userRepo.findAll

  def createUser(
    email:     String,
    firstName: String,
    lastName:  String,
    role:      UserRole,
    password:  String
  ): Txn[User] =
    val normalizedEmail = email.trim.toLowerCase
    if normalizedEmail.isBlank then throw IllegalArgumentError("email", "must not be blank")
    if userRepo.findByEmail(normalizedEmail).isDefined then
      throw IllegalArgumentError("email", "a user with this email already exists")
    if password.length < minPasswordLength then
      throw IllegalArgumentError("password", s"must be at least $minPasswordLength characters")

    val now = clock.now
    userRepo.insertReturning(
      User(
        email        = normalizedEmail,
        passwordHash = PasswordHasher.hash(password),
        firstName    = firstName,
        lastName     = lastName,
        role         = role,
        createdAt    = Some(now),
        updatedAt    = Some(now)
      )
    )

  def updateUser(
    id:        ID,
    firstName: String,
    lastName:  String,
    role:      UserRole,
    active:    Boolean
  ): Txn[User] =
    val existing = userRepo.findById(id).ErrIfNotFound("user", id)
    val updated  = existing.copy(firstName = firstName, lastName = lastName, role = role, active = active)
    updated.updatedAt = Some(clock.now)
    userRepo.update(updated)
    updated

  def resetPassword(id: ID, newPassword: String): Txn[Unit] =
    if newPassword.length < minPasswordLength then
      throw IllegalArgumentError("password", s"must be at least $minPasswordLength characters")
    val existing = userRepo.findById(id).ErrIfNotFound("user", id)
    val updated  = existing.copy(passwordHash = PasswordHasher.hash(newPassword))
    updated.updatedAt = Some(clock.now)
    userRepo.update(updated)
