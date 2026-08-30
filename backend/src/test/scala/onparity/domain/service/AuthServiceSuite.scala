package onparity.domain.service

import com.augustnagro.magnum.{DbCon, Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, UserRepoLive}
import onparity.domain.model.{User, UserRole}
import onparity.domain.port.driven.UserRepo
import onparity.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class AuthServiceSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given userRepo:   UserRepo   = UserRepoLive()
  given jwtSecret:  JwtSecret  = JwtSecret("test-secret-not-used-in-prod")

  private def activeUser(role: UserRole = UserRole.Viewer, active: Boolean = true)(using DbCon): User =
    val user = User(
      email        = s"${UUID.randomUUID()}@example.com",
      passwordHash = PasswordHasher.hash("correct-password"),
      firstName    = "Test",
      lastName     = "User",
      role         = role,
      active       = active
    )
    userRepo.insert(user)
    user

  test("login succeeds with the correct email and password, returning a valid JWT"):
    transact(transactor):
      rollBackOnly:
        val user        = activeUser(UserRole.Analyst)
        val authService = AuthService()

        val Right(token) = authService.login(user.email, "correct-password"): @unchecked

        val decoded = onparity.common.util.JwtHelper.decode(token, jwtSecret.value)
        assertEquals(decoded.map(_.userId), Right(user.id.toString))
        assertEquals(decoded.map(_.email), Right(user.email))
        assertEquals(decoded.map(_.role), Right("Analyst"))

  test("login is case-insensitive on email"):
    transact(transactor):
      rollBackOnly:
        val user        = activeUser()
        val authService = AuthService()

        val result = authService.login(user.email.toUpperCase, "correct-password")
        assert(result.isRight)

  test("login fails with the wrong password"):
    transact(transactor):
      rollBackOnly:
        val user        = activeUser()
        val authService = AuthService()

        assertEquals(authService.login(user.email, "wrong-password"), Left("Invalid email or password"))

  test("login fails for an email that doesn't exist"):
    transact(transactor):
      rollBackOnly:
        val authService = AuthService()
        assertEquals(
          authService.login(s"${UUID.randomUUID()}@example.com", "whatever"),
          Left("Invalid email or password")
        )

  test("login fails for a deactivated user, even with the correct password"):
    transact(transactor):
      rollBackOnly:
        val user        = activeUser(active = false)
        val authService = AuthService()

        assertEquals(authService.login(user.email, "correct-password"), Left("Invalid email or password"))
