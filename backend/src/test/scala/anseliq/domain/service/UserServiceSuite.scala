package anseliq.domain.service

import anseliq.domain.model.{IllegalArgumentError, NotFoundError, UserRole, createID}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.{sql, transact}

import java.util.UUID

class UserServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def randomEmail: String = s"${UUID.randomUUID()}@example.com"

  test("createUser hashes the password, normalizes the email, and returns the persisted user"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        val email       = s"MiXeD.Case.${UUID.randomUUID()}@Example.com"

        val user = userService.createUser(email, "Ada", "Lovelace", UserRole.Analyst, "a-real-password")

        assertEquals(user.email, email.toLowerCase)
        assertEquals(user.role, UserRole.Analyst)
        assertEquals(user.active, true)
        assert(user.passwordHash != "a-real-password", "password must be hashed, never stored in plaintext")
        assert(PasswordHasher.verify("a-real-password", user.passwordHash))
        assert(app.userRepo.findByEmail(email.toLowerCase).isDefined)

  test("createUser rejects a duplicate email"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        val email       = randomEmail
        userService.createUser(email, "Ada", "Lovelace", UserRole.Analyst, "a-real-password")

        intercept[IllegalArgumentError]:
          userService.createUser(email, "Someone", "Else", UserRole.Viewer, "another-password")

  test("createUser rejects a password shorter than 8 characters"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        intercept[IllegalArgumentError]:
          userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Analyst, "short")

  test("updateUser changes name/role/active without touching the password"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "a-real-password")

        val updated = userService.updateUser(created.id, "Grace", "Hopper", UserRole.Admin, active = false)

        assertEquals(updated.firstName, "Grace")
        assertEquals(updated.lastName, "Hopper")
        assertEquals(updated.role, UserRole.Admin)
        assertEquals(updated.active, false)
        assertEquals(updated.passwordHash, created.passwordHash)

  test("updateUser on a non-existent id throws NotFoundError"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        intercept[NotFoundError]:
          userService.updateUser(createID(), "Grace", "Hopper", UserRole.Admin, active = true)

  test("resetPassword replaces the password hash, verifiable with the new password only"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "original-password")

        userService.resetPassword(created.id, "brand-new-password")

        val reloaded = app.userRepo.findById(created.id).get
        assert(PasswordHasher.verify("brand-new-password", reloaded.passwordHash))
        assert(!PasswordHasher.verify("original-password", reloaded.passwordHash))

  test("resetPassword rejects a password shorter than 8 characters"):
    transact(app.transactor):
      rollBackOnly:
        val userService = app.userService
        val created     = userService.createUser(randomEmail, "Ada", "Lovelace", UserRole.Viewer, "original-password")
        intercept[IllegalArgumentError]:
          userService.resetPassword(created.id, "short")

  test("listUsers returns every user"):
    transact(app.transactor):
      rollBackOnly:
        val userService  = app.userService
        val initialCount = userService.listUsers().size

        userService.createUser(randomEmail, "A", "One", UserRole.Admin, "a-real-password")
        userService.createUser(randomEmail, "B", "Two", UserRole.Viewer, "a-real-password")

        assertEquals(userService.listUsers().size, initialCount + 2)

  // The shared test database may already have an Admin, so this deactivates every user first; the
  // rollback puts them back.
  test("createInitialAdmin creates an Admin when there's no active one"):
    transact(app.transactor):
      rollBackOnly:
        sql"update recon.app_user set active = false".update.run()
        val email = randomEmail

        val created = app.userService.createInitialAdmin(email, "first-admin-password")

        assertEquals(created.map(_.email), Some(email))
        assertEquals(created.map(_.role), Some(UserRole.Admin))
        assert(app.userService.hasActiveAdmin)

  test("createInitialAdmin creates nothing once there's an active Admin"):
    transact(app.transactor):
      rollBackOnly:
        app.userService.createUser(randomEmail, "Existing", "Admin", UserRole.Admin, "existing-password")
        val email = randomEmail

        assertEquals(app.userService.createInitialAdmin(email, "first-admin-password"), None)
        assertEquals(app.userRepo.findByEmail(email), None)
