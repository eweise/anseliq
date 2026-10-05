package anseliq.adapter.driven

import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact
import anseliq.domain.DataSetup
import anseliq.testing.rollBackOnly

class ReconciliationSpecRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Find by Account"):
    transact(app.transactor):
      rollBackOnly:
        val data            = DataSetup(app)
        val foundReconSpecs = data.reconSpecRepo.findByAccountID(data.reconAccount.id)
        assert(foundReconSpecs.nonEmpty)
