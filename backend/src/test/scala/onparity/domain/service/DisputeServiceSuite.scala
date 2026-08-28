package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, EventRepoLive, JobRepoLive, UserRepoLive}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{
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
import onparity.domain.port.driven.{JobRepo, Txn, UserRepo}
import onparity.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class DisputeServiceSuite extends munit.FunSuite:
  given transactor: Transactor   = Transactor(dataSource)
  given dataSource: DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given clockSvc:   ClockService = ClockServiceLive()
  given eventRepo: EventRepoLive()
  given eventStore: EventStore = EventStore()
  given jobRepo:    JobRepo    = JobRepoLive()
  given userRepo:   UserRepo   = UserRepoLive()

  private def testUser()(using UserRepo, com.augustnagro.magnum.DbCon): User =
    val user = User(
      email        = s"${UUID.randomUUID()}@example.com",
      passwordHash = "hash",
      firstName    = "Test",
      lastName     = "Analyst",
      role         = UserRole.Analyst
    )
    userRepo.insert(user)
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
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

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
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()
        val disputeService   = DisputeService()

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "partial match")

        val dispute = disputeService.raise(recon.id, DisputeTarget.Reconciliation, DisputeCategory.AmountMismatch, None)

        assertEquals(dispute.referenceId, recon.id)
        assertEquals(dispute.targetType, DisputeTarget.Reconciliation)
        assertEquals(dispute.category, DisputeCategory.AmountMismatch)
        assertEquals(dispute.detail, None)

  test("raise rejects a category that isn't valid for the given target"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)

        // AmountMismatch is only ever raised against a Reconciliation (see DisputeCategory.validTargets) —
        // pairing it with a Movement should be rejected before even checking whether the movement exists.
        intercept[IllegalArgumentError]:
          disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.AmountMismatch, None)

  test("raise on a nonexistent Movement errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        intercept[NotFoundError]:
          disputeService.raise(java.util.UUID.randomUUID(), DisputeTarget.Movement, DisputeCategory.DataQuality, None)

  test("raise on a nonexistent Reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        intercept[NotFoundError]:
          disputeService.raise(
            java.util.UUID.randomUUID(),
            DisputeTarget.Reconciliation,
            DisputeCategory.AmountMismatch,
            None
          )

  test("addComment to Resolved without a body errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, Some(DisputeStatus.Resolved))

  test("addComment to Resolved with a body succeeds and updates the dispute's status"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

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
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        assertEquals(comment.newStatus, Some(DisputeStatus.Investigating))
        assertEquals(comment.body, None)

        val updated = data.disputeRepo.findById(dispute.id).get
        assertEquals(updated.status, DisputeStatus.Investigating)

  test("addComment can reopen a Resolved dispute"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        disputeService.addComment(dispute.id, Some("thought this was done"), Some(DisputeStatus.Resolved))

        disputeService.addComment(dispute.id, None, Some(DisputeStatus.Investigating))

        val reopened = data.disputeRepo.findById(dispute.id).get
        assertEquals(reopened.status, DisputeStatus.Investigating)

  test("addComment with no status change requires a body"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        intercept[IllegalArgumentError]:
          disputeService.addComment(dispute.id, None, None)

  test("addComment with no status change and a body just adds a comment, leaving status unchanged"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        val comment = disputeService.addComment(dispute.id, Some("still looking into this"), None)

        assertEquals(comment.body, Some("still looking into this"))
        assertEquals(comment.newStatus, None)

        val unchanged = data.disputeRepo.findById(dispute.id).get
        assertEquals(unchanged.status, DisputeStatus.New)

  test("comments are returned in chronological order"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        disputeService.addComment(dispute.id, Some("first"), None)
        disputeService.addComment(dispute.id, Some("second"), Some(DisputeStatus.Investigating))
        disputeService.addComment(dispute.id, Some("third"), None)

        val thread = data.disputeCommentRepo.findByDisputeId(dispute.id)
        assertEquals(thread.map(_.body), Vector(Some("first"), Some("second"), Some("third")))

  test("raise with raisedByUserId persists the actor who raised it"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

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
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val movement = createMovement(data.journalToRecon, 100)
        val dispute  = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)

        assertEquals(dispute.raisedByUserId, None)

  test("assign sets assignedToUserId and bumps updatedAt"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val user              = testUser()
        val movement          = createMovement(data.journalToRecon, 100)
        val dispute           = disputeService.raise(movement.id, DisputeTarget.Movement, DisputeCategory.Duplicate, None)
        val originalUpdatedAt = dispute.updatedAt

        clockSvc.addHours(1)
        val assigned = disputeService.assign(dispute.id, user.id)

        assertEquals(assigned.assignedToUserId, Some(user.id))
        assert(assigned.updatedAt.exists(ua => originalUpdatedAt.exists(oua => ua.isAfter(oua))))

        val persisted = data.disputeRepo.findById(dispute.id).get
        assertEquals(persisted.assignedToUserId, Some(user.id))

  test("assign on a non-existent dispute throws NotFoundError"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val disputeService = DisputeService()

        val user = testUser()
        intercept[NotFoundError]:
          disputeService.assign(UUID.randomUUID(), user.id)
