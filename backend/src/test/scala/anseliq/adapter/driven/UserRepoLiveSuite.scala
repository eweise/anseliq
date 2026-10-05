package anseliq.adapter.driven

import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact
import anseliq.domain.model.*
import anseliq.testing.rollBackOnly

import java.util.UUID

class UserRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def newUser(role: UserRole = UserRole.Viewer): User =
    User(
      email        = s"${UUID.randomUUID()}@example.com",
      passwordHash = "hash",
      firstName    = "Test",
      lastName     = "User",
      role         = role
    )

  test("findById returns inserted user"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val user = newUser(UserRole.Admin)
        userRepo.insert(user)

        val found = userRepo.findById(user.id)
        assert(found.isDefined)
        assertEquals(found.get.email, user.email)
        assertEquals(found.get.role, UserRole.Admin)
        assertEquals(found.get.active, true)

  test("findById returns None for non-existent id"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val found = userRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("findByEmail finds a user by exact email match"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val user = newUser()
        userRepo.insert(user)

        val found = userRepo.findByEmail(user.email)
        assert(found.isDefined)
        assertEquals(found.get.id, user.id)

  test("findByEmail returns None for an email that doesn't exist"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val found = userRepo.findByEmail(s"${UUID.randomUUID()}@example.com")
        assert(found.isEmpty)

  test("update modifies a user"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val user = newUser(UserRole.Viewer)
        userRepo.insert(user)

        val updated = user.copy(role = UserRole.Analyst, active = false)
        userRepo.update(updated)

        val found = userRepo.findById(user.id)
        assertEquals(found.get.role, UserRole.Analyst)
        assertEquals(found.get.active, false)

  test("delete removes a user"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val user = newUser()
        userRepo.insert(user)

        userRepo.delete(user)

        val found = userRepo.findById(user.id)
        assert(found.isEmpty)

  test("findAll returns all users, including every UserRole"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val initialCount = userRepo.findAll.size

        userRepo.insert(newUser(UserRole.Admin))
        userRepo.insert(newUser(UserRole.Analyst))
        userRepo.insert(newUser(UserRole.Viewer))

        assertEquals(userRepo.findAll.size, initialCount + 3)

  test("email uniqueness is enforced at the database level"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val user = newUser()
        userRepo.insert(user)
        intercept[Exception]:
          userRepo.insert(user.copy(id = createID()))

  // Confirms the seeded schema.sql admin row (inserted as raw SQL, not through the DbCodec) is
  // actually readable — @Table(..., SqlNameMapper.CamelToSnakeCase) on UserRole lowercases enum
  // case names for storage/lookup ("Admin" -> "admin"), so a raw-SQL seed value must be lowercase
  // to round-trip; this regression-tests that the seed row in schema.sql matches.
  test("the schema.sql-seeded admin user is readable and has the Admin role"):
    val userRepo = app.userRepo
    transact(app.transactor):
      rollBackOnly:
        val found = userRepo.findByEmail("admin@onparity.local")
        assert(found.isDefined)
        assertEquals(found.get.role, UserRole.Admin)
