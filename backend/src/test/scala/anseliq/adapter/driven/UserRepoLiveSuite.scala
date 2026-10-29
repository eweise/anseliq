package anseliq.adapter.driven

import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.{sql, transact}

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
          userRepo.insert(user.copy(id = createId()))

  // A row written as raw SQL, not through the DbCodec (by a migration, say) is readable —
  // @Table(..., SqlNameMapper.CamelToSnakeCase) on UserRole lowercases enum case names for
  // storage/lookup ("Admin" -> "admin"), so a raw-SQL role value must be lowercase to round-trip.
  test("a user inserted as raw SQL with a lowercase role reads back with that role"):
    val userRepo = app.userRepo
    val email    = s"raw-${UUID.randomUUID()}@example.com"
    transact(app.transactor):
      rollBackOnly:
        sql"""insert into recon.app_user (id, email, password_hash, first_name, last_name, role)
              values (${UUID.randomUUID()}, $email, 'not-a-real-hash', 'Raw', 'Sql', 'admin')""".update.run()
        val found = userRepo.findByEmail(email)
        assert(found.isDefined)
        assertEquals(found.get.role, UserRole.Admin)
