package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{AccountRepoLive, DbDataSource, EventRepoLive, MovementRepoLive}
import perfectpair.domain.DataSetup
import perfectpair.domain.port.driven.EventRepo
import perfectpair.testing.rollBackOnly

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource

class IngestorSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo : EventRepo = EventRepoLive()
  
  given clockService:ClockService = ClockService()
  
  test("Post Amount balances"):
    val movementRepo = MovementRepoLive()
    val accountRepo  = AccountRepoLive()
    val parser       = CSVParser(Map(0 -> "field1", 1 -> "field2"), None)
    val eventStore   = EventStore()
    val ingestor     = Ingestor(using movementRepo = movementRepo, accountRepo = accountRepo, eventStore = eventStore)
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val is = ByteArrayInputStream("""value1, value2, value3
                                        |value4, value5, value6""".stripMargin.getBytes(UTF_8))
        ingestor.ingest(data.journalToRecon, is)
        val allMovements = movementRepo.findAll
        assert(allMovements.exists(_.properties.props.exists((key, value) => key == "field1" && value == "value1")))
