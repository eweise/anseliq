package anseliq.domain.service

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

// Not rolled back: ReconciliationRunner commits its own transactions (the job rows, and the
// reconciliation run's batches), so each test tears its data down explicitly.
class ReconciliationRunnerSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def jobsFor(data: DataSetup): Vector[Job] =
    transact(app.transactor)(app.jobRepo.findByJobTypeAndReferenceId(JobType.Reconciliation, data.reconAccount.id))

  private def teardown(data: DataSetup): Unit =
    transact(app.transactor):
      app.jobRepo
        .findByJobTypeAndReferenceId(JobType.Reconciliation, data.reconAccount.id)
        .foreach(j => app.jobRepo.deleteById(j.id))
      app.eventRepo
        .findByEventType("AccountReconciled")
        .filter(es => scala.util.Try(es.event[AccountReconciled].accountId == data.reconAccount.id).getOrElse(false))
        .foreach(es => app.eventRepo.deleteById(es.id))
      data.reconRepo.findByReconSpecId(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.ledgerRepo.deleteById(data.ledger.id)

  test("reconcileNow runs the account's reconciliation and completes its job"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      app.reconciliationRunner.reconcileNow(data.reconAccount.id)

      val jobs = jobsFor(data)
      assertEquals(jobs.map(_.jobStatus), Vector(JobStatus.Complete))
    finally teardown(data)

  test("reconcileNow marks the job in error, and rethrows, when the run fails"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      // A rule reconciliation can't run, saved straight through the repo the way specs could be
      // before ReconSpecService validated them.
      transact(app.transactor):
        data.reconSpecRepo.update(data.reconSpec.copy(ruleJson = upickle.write[Rule](NoOpRule)))

      val error = intercept[UnsupportedOperationException](app.reconciliationRunner.reconcileNow(data.reconAccount.id))
      assert(error.getMessage.contains("only supports"), error.getMessage)

      val jobs = jobsFor(data)
      assertEquals(jobs.map(_.jobStatus), Vector(JobStatus.Error))
      assertEquals(jobs.head.errorMessage, Some(error.getMessage))
    finally teardown(data)

  test("reconcileNow refuses, with an IllegalStateError, while the account's reconciliation is already running"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        val running = app.jobManager.submitJob(JobType.Reconciliation, data.reconAccount.id)
        app.jobManager.claimJob(running)

      intercept[IllegalStateError](app.reconciliationRunner.reconcileNow(data.reconAccount.id))

      assertEquals(jobsFor(data).map(_.jobStatus), Vector(JobStatus.InProgress))
    finally teardown(data)

  test("queueReconciliations queues one job per Recon account on the specs' sides, and none for other accounts"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        // Both specs have the Recon account on one side, so it gets one job between them.
        app.reconciliationRunner.queueReconciliations(Set(data.journalToRecon.id, data.reportToRecon.id))

        def jobStatuses(accountId: ID) =
          app.jobRepo.findByJobTypeAndReferenceId(JobType.Reconciliation, accountId).map(_.jobStatus)
        assertEquals(jobStatuses(data.reconAccount.id), Vector(JobStatus.Submitted))
        assertEquals(jobStatuses(data.journalAccount.id), Vector.empty)
        assertEquals(jobStatuses(data.reportAccount.id), Vector.empty)
