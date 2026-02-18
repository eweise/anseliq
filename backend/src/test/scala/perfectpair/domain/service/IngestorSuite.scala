package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, MovementRepoLive}
import perfectpair.domain.DataSetup
import perfectpair.testing.rollBackOnly

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class IngestorSuite extends munit.FunSuite:
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given transactor: Transactor = Transactor(dataSource)

  test("Post Amount balances"):
    val movementRepo = MovementRepoLive()
    val parser       = CSVParser(Map(0 -> "field1", 1 -> "field2"), None)
    val ingestor     = Ingestor(movementRepo = movementRepo)
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val is = ByteArrayInputStream("""value1, value2, value3
                                        |value4, value5, value6""".stripMargin.getBytes(UTF_8))
        ingestor.ingest(data.journalToRecon, is)
        val allMovements = movementRepo.findAll
        assert(allMovements.exists(_.properties.props.exists((key, value) => key == "field1" && value == "value1")))
