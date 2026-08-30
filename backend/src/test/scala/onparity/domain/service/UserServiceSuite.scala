package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, UserRepoLive}
import onparity.domain.model.{IllegalArgumentError, NotFoundError, UserRole, createID}
import onparity.domain.port.driven.UserRepo
import onparity.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class UserServiceSuite extends munit.FunSuite:
  given transactor: Transactor   = Transactor(dataSource)
  given dataSource: DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given userRepo:   UserRepo     = UserRepoLive()
  given clockSvc:   ClockService = ClockServiceLive()

  private def randomEmail: String = s"${UUID.randomUUID()}@example.com"

  test("createUser hashes the password, normalizes the email, and returns the persisted user"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        val email       = s"MiXeD.Case.${UUID.randomUUID()}@Example.com"

        val user = userService.createUser(email, "Ada", "Lovelace", UserRole.Analyst, "a-real-password")

        assertEquals(user.email, email.toLowerCase)
        assertEquals(user.role, UserRole.Analyst)
        assertEquals(user.active, true)
        assert(user.passwordHash != "a-real-password", "password must be hashed, never stored in plaintext")
        assert(PasswordHasher.verify("a-real-password", user.passwordHash))
        assert(userRepo.findByEmail(email.toLowerCase).isDefined)

  test("createUser rejects a duplicate email"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        val email       = randomEmail
        userService.createUser(email, "Ada", "Lovelace", UserRole.Analyst, "a-real-password")

        intercept[IllegalArgumentError]:
          userService.createUser(email, "Someone", "Else", UserRole.Viewer, "another-password")

  test("createUser rejects a password shorter than 8 characters"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        intercept[IllegalArgumentError]:
          userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Analyst, "short")

  test("updateUser changes name/role/active without touching the password"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "a-real-password")

        val updated = userService.updateUser(created.id, "Grace", "Hopper", UserRole.Admin, active = false)

        assertEquals(updated.firstName, "Grace")
        assertEquals(updated.lastName, "Hopper")
        assertEquals(updated.role, UserRole.Admin)
        assertEquals(updated.active, false)
        assertEquals(updated.passwordHash, created.passwordHash)

  test("updateUser on a non-existent id throws NotFoundError"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        intercept[NotFoundError]:
          userService.updateUser(createID(), "Grace", "Hopper", UserRole.Admin, active = true)

  test("resetPassword replaces the password hash, verifiable with the new password only"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "original-password")

        userService.resetPassword(created.id, "brand-new-password")

        val reloaded = userRepo.findById(created.id).get
        assert(PasswordHasher.verify("brand-new-password", reloaded.passwordHash))
        assert(!PasswordHasher.verify("original-password", reloaded.passwordHash))

  test("resetPassword rejects a password shorter than 8 characters"):
    transact(transactor):
      rollBackOnly:
        val userService = UserService()
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "original-password")
        intercept[IllegalArgumentError]:
          userService.resetPassword(created.id, "short")

  test("listUsers returns every user"):
    transact(transactor):
      rollBackOnly:
        val userService  = UserService()
        val initialCount = userService.listUsers().size

        userService.createUser(randomEmail, "A", "One", UserRole.Admin, "a-real-password")
        userService.createUser(randomEmail, "B", "Two", UserRole.Viewer, "a-real-password")

        assertEquals(userService.listUsers().size, initialCount + 2)
