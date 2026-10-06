package anseliq.domain.service

import anseliq.domain.model.{User, UserRole}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.{DbCon, transact}

import java.util.UUID

class AuthServiceSuite extends munit.FunSuite:
  private val app = new TestAppContainer {
    override lazy val jwtSecret: JwtSecret = JwtSecret("test-secret-not-used-in-prod")
  }

  private def activeUser(role: UserRole = UserRole.Viewer, active: Boolean = true)(using DbCon): User =
    val user = User(
      email        = s"${UUID.randomUUID()}@example.com",
      passwordHash = PasswordHasher.hash("correct-password"),
      firstName    = "Test",
      lastName     = "User",
      role         = role,
      active       = active
    )
    app.userRepo.insert(user)
    user

  test("login succeeds with the correct email and password, returning a valid JWT"):
    transact(app.transactor):
      rollBackOnly:
        val user        = activeUser(UserRole.Analyst)
        val authService = app.authService

        val Right(token) = authService.login(user.email, "correct-password"): @unchecked

        val decoded = anseliq.common.util.JwtHelper.decode(token, app.jwtSecret.value)
        assertEquals(decoded.map(_.userId), Right(user.id.toString))
        assertEquals(decoded.map(_.email), Right(user.email))
        assertEquals(decoded.map(_.role), Right("Analyst"))

  test("login is case-insensitive on email"):
    transact(app.transactor):
      rollBackOnly:
        val user        = activeUser()
        val authService = app.authService

        val result = authService.login(user.email.toUpperCase, "correct-password")
        assert(result.isRight)

  test("login fails with the wrong password"):
    transact(app.transactor):
      rollBackOnly:
        val user        = activeUser()
        val authService = app.authService

        assertEquals(authService.login(user.email, "wrong-password"), Left("Invalid email or password"))

  test("login fails for an email that doesn't exist"):
    transact(app.transactor):
      rollBackOnly:
        val authService = app.authService
        assertEquals(
          authService.login(s"${UUID.randomUUID()}@example.com", "whatever"),
          Left("Invalid email or password")
        )

  test("login fails for a deactivated user, even with the correct password"):
    transact(app.transactor):
      rollBackOnly:
        val user        = activeUser(active = false)
        val authService = app.authService

        assertEquals(authService.login(user.email, "correct-password"), Left("Invalid email or password"))
