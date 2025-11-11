package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.DbDataSource
import com.eweise.perfectpair.domain.model.Reconciliation
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class ReconciliationRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("Create Reconciliation"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.reconRepo.insert(Reconciliation(description = "hey"))
