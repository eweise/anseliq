package anseliq.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.domain.model.{createID, Dispute, DisputeCategory, DisputeStatus, DisputeTarget}
import anseliq.testing.rollBackOnly

import java.time.OffsetDateTime
import javax.sql.DataSource

class DisputeRepoLiveSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  private def amountMismatch(referenceId: anseliq.common.model.model.ID): Dispute =
    val now = OffsetDateTime.now()
    Dispute(
      referenceId = referenceId,
      targetType  = DisputeTarget.Reconciliation,
      category    = DisputeCategory.AmountMismatch,
      detail      = Some("balance mismatch of 2"),
      createdAt   = Some(now),
      updatedAt   = Some(now)
    )

  test("insertIfNoOpenDispute inserts the first time and no-ops on a duplicate open dispute"):
    val disputeRepo = DisputeRepoLive()
    transact(transactor):
      rollBackOnly:
        val referenceId = createID()

        val first = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId))
        assert(first.isDefined, "first insert for a fresh (referenceId, category) pair should succeed")

        val second = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId))
        assertEquals(second, None, "a second open dispute for the same (referenceId, category) pair must be a no-op")

        assertEquals(disputeRepo.findAll.count(_.referenceId == referenceId), 1)

  test("insertIfNoOpenDispute allows a fresh dispute once the prior one for the same pair is resolved"):
    val disputeRepo = DisputeRepoLive()
    transact(transactor):
      rollBackOnly:
        val referenceId = createID()

        val first = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId)).get
        disputeRepo.update(first.copy(status = DisputeStatus.Resolved))

        val second = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId))
        assert(second.isDefined, "a resolved prior dispute must not block a fresh one for the same pair")

        assertEquals(disputeRepo.findAll.count(_.referenceId == referenceId), 2)

  test("insertIfNoOpenDispute allows different categories for the same referenceId"):
    val disputeRepo = DisputeRepoLive()
    transact(transactor):
      rollBackOnly:
        val referenceId = createID()

        val amountMismatchDispute = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId))
        val dataQualityDispute = disputeRepo.insertIfNoOpenDispute(
          amountMismatch(referenceId).copy(category = DisputeCategory.DataQuality)
        )

        assert(amountMismatchDispute.isDefined)
        assert(dataQualityDispute.isDefined, "a different category for the same referenceId is not a duplicate")

  test("findOpenByReferenceId returns every unresolved dispute for the target, across categories"):
    val disputeRepo = DisputeRepoLive()
    transact(transactor):
      rollBackOnly:
        val referenceId = createID()

        val mismatch = disputeRepo.insertIfNoOpenDispute(amountMismatch(referenceId)).get
        val dataQuality = disputeRepo
          .insertIfNoOpenDispute(amountMismatch(referenceId).copy(category = DisputeCategory.DataQuality))
          .get
        disputeRepo.update(dataQuality.copy(status = DisputeStatus.Resolved))
        val duplicate = disputeRepo
          .insertIfNoOpenDispute(amountMismatch(referenceId).copy(category = DisputeCategory.Duplicate))
          .get
        disputeRepo.insertIfNoOpenDispute(amountMismatch(createID())) // another target entirely

        assertEquals(disputeRepo.findOpenByReferenceId(referenceId).map(_.id).toSet, Set(mismatch.id, duplicate.id))
