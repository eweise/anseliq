package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class ReconciliationSpecRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Find by Account"):
    transact(app.transactor):
      rollBackOnly:
        val data            = DataSetup(app)
        val foundReconSpecs = data.reconSpecRepo.findByAccountId(data.reconAccount.id)
        assert(foundReconSpecs.nonEmpty)
