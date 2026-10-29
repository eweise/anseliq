package anseliq.adapter.driven

import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.time.OffsetDateTime

class ExceptionRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def amountMismatch(referenceId: anseliq.common.model.model.ID): ExceptionCase =
    val now = OffsetDateTime.now()
    ExceptionCase(
      referenceId = referenceId,
      targetType  = ExceptionTarget.Reconciliation,
      category    = ExceptionCategory.AmountMismatch,
      detail      = Some("balance mismatch of 2"),
      createdAt   = Some(now),
      updatedAt   = Some(now)
    )

  test("insertIfNoOpenException inserts the first time and no-ops on a duplicate open exception"):
    val exceptionRepo = app.exceptionRepo
    transact(app.transactor):
      rollBackOnly:
        val referenceId = createId()

        val first = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId))
        assert(first.isDefined, "first insert for a fresh (referenceId, category) pair should succeed")

        val second = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId))
        assertEquals(second, None, "a second open exception for the same (referenceId, category) pair must be a no-op")

        assertEquals(exceptionRepo.findAll.count(_.referenceId == referenceId), 1)

  test("insertIfNoOpenException allows a fresh exception once the prior one for the same pair is resolved"):
    val exceptionRepo = app.exceptionRepo
    transact(app.transactor):
      rollBackOnly:
        val referenceId = createId()

        val first = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId)).get
        exceptionRepo.update(first.copy(status = ExceptionStatus.Resolved))

        val second = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId))
        assert(second.isDefined, "a resolved prior exception must not block a fresh one for the same pair")

        assertEquals(exceptionRepo.findAll.count(_.referenceId == referenceId), 2)

  test("insertIfNoOpenException allows different categories for the same referenceId"):
    val exceptionRepo = app.exceptionRepo
    transact(app.transactor):
      rollBackOnly:
        val referenceId = createId()

        val amountMismatchException = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId))
        val dataQualityException = exceptionRepo.insertIfNoOpenException(
          amountMismatch(referenceId).copy(category = ExceptionCategory.DataQuality)
        )

        assert(amountMismatchException.isDefined)
        assert(dataQualityException.isDefined, "a different category for the same referenceId is not a duplicate")

  test("findOpenByReferenceId returns every unresolved exception for the target, across categories"):
    val exceptionRepo = app.exceptionRepo
    transact(app.transactor):
      rollBackOnly:
        val referenceId = createId()

        val mismatch = exceptionRepo.insertIfNoOpenException(amountMismatch(referenceId)).get
        val dataQuality = exceptionRepo
          .insertIfNoOpenException(amountMismatch(referenceId).copy(category = ExceptionCategory.DataQuality))
          .get
        exceptionRepo.update(dataQuality.copy(status = ExceptionStatus.Resolved))
        val duplicate = exceptionRepo
          .insertIfNoOpenException(amountMismatch(referenceId).copy(category = ExceptionCategory.Duplicate))
          .get
        exceptionRepo.insertIfNoOpenException(amountMismatch(createId())) // another target entirely

        assertEquals(exceptionRepo.findOpenByReferenceId(referenceId).map(_.id).toSet, Set(mismatch.id, duplicate.id))
