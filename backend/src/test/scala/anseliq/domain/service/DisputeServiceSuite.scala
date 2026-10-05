package anseliq.domain.service

import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact
import anseliq.domain.DataSetup
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.model.{
  DisputeCategory,
  DisputeStatus,
  DisputeTarget,
  IllegalArgumentError,
  Movement,
  MovementSpec,
  NotFoundError,
  User,
  UserRole
}
import anseliq.domain.port.driven.Txn
import anseliq.testing.rollBackOnly

import java.util.UUID

class DisputeServiceSuite extends munit.FunSuite:
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

  test("raise on a Movement creates a New dispute"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)

        val dispute = disputeService.raise(
          movement.id,
          DisputeTarget.Movement,
          DisputeCategory.MissingCounterpart,
          Some("no match found")
        )

        assertEquals(dispute.referenceId, movement.id)
        assertEquals(dispute.targetType, DisputeTarget.Movement)
        assertEquals(dispute.category, DisputeCategory.MissingCounterpart)
        assertEquals(dispute.detail, Some("no match found"))
        assertEquals(dispute.status, DisputeStatus.New)
        assert(dispute.createdAt.isDefined, "createdAt must be set on the returned dispute, not just the persisted row")

        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.referenceId, movement.id)

  test("raise on a Reconciliation creates a New dispute"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val manualReconciler = app.manualReconciler
        val disputeService   = app.disputeService

        // Balanced, so matchMovements leaves it Final without raising its own AmountMismatch
        // dispute — an unbalanced match would already have one open, and this would conflict.
        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 300)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "match")

        val dispute = disputeService.raise(recon.id, DisputeTarget.Reconciliation, DisputeCategory.AmountMismatch, None)

        assertEquals(dispute.referenceId, recon.id)
        assertEquals(dispute.targetType, DisputeTarget.Reconciliation)
        assertEquals(dispute.category, DisputeCategory.AmountMismatch)
        assertEquals(dispute.detail, None)

  test("raise rejects a category that isn't valid for the given target"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)

        // AmountMismatch is only ever raised against a Reconciliation (see DisputeCategory.validTargets) —
        // pairing it with a Movement should be rejected before even checking whether the movement exists.
        intercept[IllegalArgumentError]:
          disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.AmountMismatch, None)

  test("raise on a nonexistent Movement errors"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        intercept[NotFoundError]:
          disputeService.raise(java.util.UUID.randomUUID(), DisputeTarget.Movement, DisputeCategory.DataQuality, None)

  test("raise on a nonexistent Reconciliation errors"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        intercept[NotFoundError]:
          disputeService.raise(
            java.util.UUID.randomUUID(),
            DisputeTarget.Reconciliation,
            DisputeCategory.AmountMismatch,
            None
          )

  test("addComment to Resolved without a body errors"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, Some(DisputeStatus.Resolved))

  test("addComment to Resolved with a body succeeds and updates the dispute's status"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(
          dispute.id,
          Some("was a re-ingested duplicate, deleted downstream"),
          Some(DisputeStatus.Resolved)
        )

        assertEquals(comment.body, Some("was a re-ingested duplicate, deleted downstream"))
        assertEquals(comment.newStatus, Some(DisputeStatus.Resolved))

        val updated = data.disputeRepo.findById(dispute.id).get
        assertEquals(updated.status, DisputeStatus.Resolved)

  test("addComment to a non-Resolved status does not require a body"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        assertEquals(comment.newStatus, Some(DisputeStatus.Investigating))
        assertEquals(comment.body, None)

        val updated = data.disputeRepo.findById(dispute.id).get
        assertEquals(updated.status, DisputeStatus.Investigating)

  test("addComment can reopen a Resolved dispute"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        disputeService.addComment(dispute.id, Some("thought this was done"), Some(DisputeStatus.Resolved))

        disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        val reopened = data.disputeRepo.findById(dispute.id).get
        assertEquals(reopened.status, DisputeStatus.Investigating)

  test("addComment with no status change requires a body"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, None)

  test("addComment with no status change and a body just adds a comment, leaving status unchanged"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, Some("still looking into this"), None)

        assertEquals(comment.body, Some("still looking into this"))
        assertEquals(comment.newStatus, None)

        val unchanged = data.disputeRepo.findById(dispute.id).get
        assertEquals(unchanged.status, DisputeStatus.New)

  test("comments are returned in chronological order"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        disputeService.addComment(dispute.id, Some("first"), None)
        disputeService.addComment(dispute.id, Some("second"), Some(DisputeStatus.Investigating))
        disputeService.addComment(dispute.id, Some("third"), None)

        val thread = data.disputeCommentRepo.findByDisputeId(dispute.id)
        assertEquals(thread.map(_.body), Vector(Some("first"), Some("second"), Some("third")))

  test("raise with raisedByUserId persists the actor who raised it"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user     = testUser()
        val movement = createMovement(data.journalToRecon, 100)

        val dispute = disputeService.raise(
          movement.id,
          DisputeTarget.Movement,
          DisputeCategory.MissingCounterpart,
          None,
          raisedByUserId = Some(user.id)
        )

        assertEquals(dispute.raisedByUserId, Some(user.id))
        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.raisedByUserId, Some(user.id))

  test("raise with no raisedByUserId leaves it unset (system-raised)"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        assertEquals(dispute.raisedByUserId, None)

  test("addComment with newAssignedToUserId sets assignedToUserId, bumps updatedAt, and logs it on the thread"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user              = testUser()
        val movement          = createMovement(data.journalToRecon, 100)
        val dispute           = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        val originalUpdatedAt = dispute.updatedAt

        app.clock.addHours(1)
        val comment =
          disputeService.addComment(dispute.id, body = None, newStatus = None, newAssignedToUserId = Some(user.id))

        assertEquals(comment.newAssignedToUserId, Some(user.id))

        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.assignedToUserId, Some(user.id))
        assert(persisted.updatedAt.exists(ua => originalUpdatedAt.exists(oua => ua.isAfter(oua))))

  test("addComment can assign with a message in the same entry"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user     = testUser()
        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(
          dispute.id,
          body                = Some("please take a look"),
          newStatus           = None,
          newAssignedToUserId = Some(user.id)
        )

        assertEquals(comment.newAssignedToUserId, Some(user.id))
        assertEquals(comment.body, Some("please take a look"))

  test("addComment rejects assigning a dispute to a Viewer"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val viewer   = testUser(role = UserRole.Viewer)
        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, body = None, newStatus = None, newAssignedToUserId = Some(viewer.id))

        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.assignedToUserId, None)

  test("addComment with newAssignedToUserId on a non-existent dispute throws NotFoundError"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user = testUser()
        intercept[NotFoundError]:
          disputeService.addComment(UUID.randomUUID(), body = None, newStatus = None, newAssignedToUserId = Some(user.id))

  // Resolving an AmountMismatch by hand would hide a reconciliation that's still Mismatched; only a
  // write-off, unmatch or late completion may resolve it (see DisputeCategory.automaticResolution).
  test("addComment rejects resolving an AmountMismatch by hand but still allows other status changes"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val manualReconciler = app.manualReconciler
        val disputeService   = app.disputeService

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "mismatch")
        val dispute =
          data.disputeRepo.findOpenByReferenceIdAndCategory(recon.id, DisputeCategory.AmountMismatch).get

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, Some("looks fine"), Some(DisputeStatus.Resolved))
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.New)

        disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.Investigating)

  // Once the system has resolved an AmountMismatch (here by the write-off), nothing would ever
  // resolve it again, so a person can't reopen it; undoing the write-off (unmatch) is the way back.
  test("addComment rejects reopening an AmountMismatch the system resolved, but still allows comments"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val manualReconciler = app.manualReconciler
        val disputeService   = app.disputeService

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "mismatch")
        val dispute =
          data.disputeRepo.findOpenByReferenceIdAndCategory(recon.id, DisputeCategory.AmountMismatch).get
        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.Resolved)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, Some("reopening"), Some(DisputeStatus.New))
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.Resolved)

        disputeService.addComment(dispute.id, Some("written off as the monthly bank fee"), None)
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.Resolved)

  test("addComment rejects assigning a resolved dispute, but allows assigning in the entry that reopens it"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user     = testUser()
        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        disputeService.addComment(dispute.id, Some("not a duplicate"), Some(DisputeStatus.Resolved))

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, body = None, newStatus = None, newAssignedToUserId = Some(user.id))
        assertEquals(data.disputeRepo.findById(dispute.id).get.assignedToUserId, None)

        disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating), Some(user.id))
        val reopened = data.disputeRepo.findById(dispute.id).get
        assertEquals(reopened.status, DisputeStatus.Investigating)
        assertEquals(reopened.assignedToUserId, Some(user.id))

  test("addComment rejects resolving and assigning in the same entry"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        given DataSetup = data
        val disputeService = app.disputeService

        val user     = testUser()
        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, Some("done"), Some(DisputeStatus.Resolved), Some(user.id))
        assertEquals(data.disputeRepo.findById(dispute.id).get.status, DisputeStatus.New)
