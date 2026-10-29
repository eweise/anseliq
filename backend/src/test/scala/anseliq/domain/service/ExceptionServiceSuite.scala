package anseliq.domain.service

import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.Txn
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class ExceptionServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def testUser(role: UserRole = UserRole.Analyst)(using com.augustnagro.magnum.DbCon): User =
    val user = User(
      email        = s"${UUID.randomUUID()}@example.com",
      passwordHash = "hash",
      firstName    = "Test",
      lastName     = "Analyst",
      role         = role
    )
    app.userRepo.insert(user)
    user

  def createMovement(movementSpec: MovementSpec, amount: BigDecimal)(using data: DataSetup): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  test("raise on a Movement creates a New exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement = createMovement(data.journalToRecon, 100)

        val exception = exceptionService.raise(
          movement.id,
          ExceptionTarget.Movement,
          ExceptionCategory.MissingCounterpart,
          Some("no match found")
        )

        assertEquals(exception.referenceId, movement.id)
        assertEquals(exception.targetType, ExceptionTarget.Movement)
        assertEquals(exception.category, ExceptionCategory.MissingCounterpart)
        assertEquals(exception.detail, Some("no match found"))
        assertEquals(exception.status, ExceptionStatus.New)
        assert(
          exception.createdAt.isDefined,
          "createdAt must be set on the returned exception, not just the persisted row"
        )

        val persisted = data.exceptionRepo.findById(exception.id).get
        assertEquals(persisted.referenceId, movement.id)

  test("raise on a Reconciliation creates a New exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler
        val exceptionService = app.exceptionService

        // Balanced, so matchMovements leaves it Final without raising its own AmountMismatch
        // exception — an unbalanced match would already have one open, and this would conflict.
        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 300)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "match")

        val exception =
          exceptionService.raise(recon.id, ExceptionTarget.Reconciliation, ExceptionCategory.AmountMismatch, None)

        assertEquals(exception.referenceId, recon.id)
        assertEquals(exception.targetType, ExceptionTarget.Reconciliation)
        assertEquals(exception.category, ExceptionCategory.AmountMismatch)
        assertEquals(exception.detail, None)

  test("raise rejects a category that isn't valid for the given target"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement = createMovement(data.journalToRecon, 100)

        // AmountMismatch is only ever raised against a Reconciliation (see ExceptionCategory.validTargets) —
        // pairing it with a Movement should be rejected before even checking whether the movement exists.
        intercept[IllegalArgumentError]:
          exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.AmountMismatch, None)

  test("raise on a nonexistent Movement errors"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        intercept[NotFoundError]:
          exceptionService.raise(
            java.util.UUID.randomUUID(),
            ExceptionTarget.Movement,
            ExceptionCategory.DataQuality,
            None
          )

  test("raise on a nonexistent Reconciliation errors"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        intercept[NotFoundError]:
          exceptionService.raise(
            java.util.UUID.randomUUID(),
            ExceptionTarget.Reconciliation,
            ExceptionCategory.AmountMismatch,
            None
          )

  test("addComment to Resolved without a body errors"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, None, Some(ExceptionStatus.Resolved))

  test("addComment to Resolved with a body succeeds and updates the exception's status"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        val comment = exceptionService.addComment(
          exception.id,
          Some("was a re-ingested duplicate, deleted downstream"),
          Some(ExceptionStatus.Resolved)
        )

        assertEquals(comment.body, Some("was a re-ingested duplicate, deleted downstream"))
        assertEquals(comment.newStatus, Some(ExceptionStatus.Resolved))

        val updated = data.exceptionRepo.findById(exception.id).get
        assertEquals(updated.status, ExceptionStatus.Resolved)

  test("addComment to a non-Resolved status does not require a body"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        val comment = exceptionService.addComment(exception.id, None, Some(ExceptionStatus.Investigating))

        assertEquals(comment.newStatus, Some(ExceptionStatus.Investigating))
        assertEquals(comment.body, None)

        val updated = data.exceptionRepo.findById(exception.id).get
        assertEquals(updated.status, ExceptionStatus.Investigating)

  test("addComment can reopen a Resolved exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)
        exceptionService.addComment(exception.id, Some("thought this was done"), Some(ExceptionStatus.Resolved))

        exceptionService.addComment(exception.id, None, Some(ExceptionStatus.Investigating))

        val reopened = data.exceptionRepo.findById(exception.id).get
        assertEquals(reopened.status, ExceptionStatus.Investigating)

  test("addComment with no status change requires a body"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, None, None)

  test("addComment with no status change and a body just adds a comment, leaving status unchanged"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        val comment = exceptionService.addComment(exception.id, Some("still looking into this"), None)

        assertEquals(comment.body, Some("still looking into this"))
        assertEquals(comment.newStatus, None)

        val unchanged = data.exceptionRepo.findById(exception.id).get
        assertEquals(unchanged.status, ExceptionStatus.New)

  test("comments are returned in chronological order"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        exceptionService.addComment(exception.id, Some("first"), None)
        exceptionService.addComment(exception.id, Some("second"), Some(ExceptionStatus.Investigating))
        exceptionService.addComment(exception.id, Some("third"), None)

        val thread = data.exceptionCommentRepo.findByExceptionId(exception.id)
        assertEquals(thread.map(_.body), Vector(Some("first"), Some("second"), Some("third")))

  test("raise with raisedByUserId persists the actor who raised it"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user     = testUser()
        val movement = createMovement(data.journalToRecon, 100)

        val exception = exceptionService.raise(
          movement.id,
          ExceptionTarget.Movement,
          ExceptionCategory.MissingCounterpart,
          None,
          raisedByUserId = Some(user.id)
        )

        assertEquals(exception.raisedByUserId, Some(user.id))
        val persisted = data.exceptionRepo.findById(exception.id).get
        assertEquals(persisted.raisedByUserId, Some(user.id))

  test("raise with no raisedByUserId leaves it unset (system-raised)"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        assertEquals(exception.raisedByUserId, None)

  test("addComment with newAssignedToUserId sets assignedToUserId, bumps updatedAt, and logs it on the thread"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user              = testUser()
        val movement          = createMovement(data.journalToRecon, 100)
        val exception         = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)
        val originalUpdatedAt = exception.updatedAt

        app.clock.addHours(1)
        val comment =
          exceptionService.addComment(exception.id, body = None, newStatus = None, newAssignedToUserId = Some(user.id))

        assertEquals(comment.newAssignedToUserId, Some(user.id))

        val persisted = data.exceptionRepo.findById(exception.id).get
        assertEquals(persisted.assignedToUserId, Some(user.id))
        assert(persisted.updatedAt.exists(ua => originalUpdatedAt.exists(oua => ua.isAfter(oua))))

  test("addComment can assign with a message in the same entry"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user      = testUser()
        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        val comment = exceptionService.addComment(
          exception.id,
          body                = Some("please take a look"),
          newStatus           = None,
          newAssignedToUserId = Some(user.id)
        )

        assertEquals(comment.newAssignedToUserId, Some(user.id))
        assertEquals(comment.body, Some("please take a look"))

  test("addComment rejects assigning an exception to a Viewer"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val viewer    = testUser(role = UserRole.Viewer)
        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          exceptionService.addComment(
            exception.id,
            body                = None,
            newStatus           = None,
            newAssignedToUserId = Some(viewer.id)
          )

        val persisted = data.exceptionRepo.findById(exception.id).get
        assertEquals(persisted.assignedToUserId, None)

  test("addComment with newAssignedToUserId on a non-existent exception throws NotFoundError"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user = testUser()
        intercept[NotFoundError]:
          exceptionService.addComment(
            UUID.randomUUID(),
            body                = None,
            newStatus           = None,
            newAssignedToUserId = Some(user.id)
          )

  // Resolving an AmountMismatch by hand would hide a reconciliation that's still Mismatched; only a
  // write-off, unmatch or late completion may resolve it (see ExceptionCategory.automaticResolution).
  test("addComment rejects resolving an AmountMismatch by hand but still allows other status changes"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler
        val exceptionService = app.exceptionService

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "mismatch")
        val exception =
          data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch).get

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, Some("looks fine"), Some(ExceptionStatus.Resolved))
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.New)

        exceptionService.addComment(exception.id, None, Some(ExceptionStatus.Investigating))
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.Investigating)

  // Once the system has resolved an AmountMismatch (here by the write-off), nothing would ever
  // resolve it again, so a person can't reopen it; undoing the write-off (unmatch) is the way back.
  test("addComment rejects reopening an AmountMismatch the system resolved, but still allows comments"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler
        val exceptionService = app.exceptionService

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "mismatch")
        val exception =
          data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch).get
        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.Resolved)

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, Some("reopening"), Some(ExceptionStatus.New))
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.Resolved)

        exceptionService.addComment(exception.id, Some("written off as the monthly bank fee"), None)
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.Resolved)

  test("addComment rejects assigning a resolved exception, but allows assigning in the entry that reopens it"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user      = testUser()
        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)
        exceptionService.addComment(exception.id, Some("not a duplicate"), Some(ExceptionStatus.Resolved))

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, body = None, newStatus = None, newAssignedToUserId = Some(user.id))
        assertEquals(data.exceptionRepo.findById(exception.id).get.assignedToUserId, None)

        exceptionService.addComment(exception.id, None, Some(ExceptionStatus.Investigating), Some(user.id))
        val reopened = data.exceptionRepo.findById(exception.id).get
        assertEquals(reopened.status, ExceptionStatus.Investigating)
        assertEquals(reopened.assignedToUserId, Some(user.id))

  test("addComment rejects resolving and assigning in the same entry"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val exceptionService = app.exceptionService

        val user      = testUser()
        val movement  = createMovement(data.journalToRecon, 100)
        val exception = exceptionService.raise(movement.id, ExceptionTarget.Movement, ExceptionCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          exceptionService.addComment(exception.id, Some("done"), Some(ExceptionStatus.Resolved), Some(user.id))
        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.New)
