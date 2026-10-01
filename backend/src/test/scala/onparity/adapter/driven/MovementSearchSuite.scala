package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.common.model.AppliedRules
import onparity.common.model.model.ID
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.model.helper.JSONBProps
import onparity.domain.port.driven.*
import onparity.domain.service.{ClockService, ClockServiceLive, FundFlowCache, FundFlowOperations, Ledger}

import java.time.OffsetDateTime
import javax.sql.DataSource

class MovementSearchSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given movementRepo:       MovementRepo           = MovementRepoLive()
  given accountRepo:        AccountRepo            = AccountRepoLive()
  given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  given reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given disputeRepo:        DisputeRepo            = DisputeRepoLive()
  given disputeCommentRepo: DisputeCommentRepo     = DisputeCommentRepoLive()
  given clockService:       ClockService           = ClockServiceLive()
  given ledger:             Ledger                 = Ledger()
  given fundFlowCache:      FundFlowCache          = FundFlowCache(fundFlowConfigRepo)

  private def teardown(data: DataSetup): Unit =
    transact(transactor):
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  private def insertMovement(
    spec:         MovementSpec,
    amount:       BigDecimal,
    props:        Map[String, String]  = Map.empty,
    metaData:     Map[String, String]  = Map.empty,
    movementType: Option[MovementType] = None
  ): Movement =
    transact(transactor):
      val movement = Movement(
        inAccountId    = spec.inAccountId,
        outAccountId   = spec.outAccountId,
        movementSpecId = spec.id,
        amount         = amount,
        properties     = JSONBProps(props),
        metaData       = JSONBProps(metaData),
        movementType   = movementType
      )
      movementRepo.insert(movement)
      movement

  test(
    "search filters by account, amount, movement spec, properties, metadata prefix, movement type, date range, and reconciliation filter"
  ):
    val data = transact(transactor)(DataSetup())
    try
      val m1 = insertMovement(
        data.journalToRecon,
        100,
        props        = Map("id" -> "1", "category" -> "fee"),
        metaData     = Map("file_source_entry" -> "bucketA|fileA"),
        movementType = Some(MovementType.Ingestion)
      )
      val m2 = insertMovement(
        data.journalToRecon,
        200,
        props        = Map("id" -> "2", "category" -> "payment"),
        metaData     = Map("file_source_entry" -> "bucketA|fileB"),
        movementType = Some(MovementType.ManualAdjustment)
      )
      val m3 = insertMovement(
        data.reportToRecon,
        100,
        props    = Map("id" -> "3"),
        metaData = Map("file_source_entry" -> "bucketB|fileC")
      )

      def searchFor(criteria: MovementSearchCriteria): Set[ID] =
        transact(transactor)(movementRepo.search(criteria)).map(_.id).toSet

      // Account: journalAccount sits on the "out" side of journalToRecon's movements (m1, m2) only
      assertEquals(
        searchFor(
          MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, accountId = Some(data.journalAccount.id))
        ),
        Set(m1.id, m2.id)
      )

      // Amount: exact match
      assertEquals(
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, amount = Some(BigDecimal(100)))),
        Set(m1.id, m3.id)
      )

      // Movement spec
      assertEquals(
        searchFor(
          MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, movementSpecId = Some(data.reportToRecon.id))
        ),
        Set(m3.id)
      )

      // Properties: jsonb containment, only m1 has category=fee
      assertEquals(
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, properties = Map("category" -> "fee"))),
        Set(m1.id)
      )

      // Metadata: substring match against the whole "bucket|key" value, case-insensitive, matching
      // anywhere — not anchored to the start, since the bucket portion is no longer user-visible.
      assertEquals(
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, fileSourceQuery = Some("bucketA"))),
        Set(m1.id, m2.id)
      )
      assertEquals(
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, fileSourceQuery = Some("fileB"))),
        Set(m2.id)
      )
      assertEquals(
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, fileSourceQuery = Some("|filec"))),
        Set(m3.id)
      )

      // Movement type: m3 was inserted with no movementType (None), so it's excluded from both
      // specific-type searches, not swept in by a null-matches-anything comparison.
      assertEquals(
        searchFor(
          MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, movementType = Some(MovementType.Ingestion))
        ),
        Set(m1.id)
      )
      assertEquals(
        searchFor(
          MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, movementType = Some(MovementType.ManualAdjustment))
        ),
        Set(m2.id)
      )

      // Date range: created_at defaults to now() at insert time (no test-controllable clock for
      // this column), so a wide window around "now" should include everything and a window
      // clearly in the past should include nothing — proving the filter is wired up correctly
      // without needing to control the exact timestamp.
      val now = OffsetDateTime.now()
      assertEquals(
        searchFor(
          MovementSearchCriteria(
            fundFlowId  = data.fundFlowConfig.id,
            createdFrom = Some(now.minusDays(1)),
            createdTo   = Some(now.plusDays(1))
          )
        ),
        Set(m1.id, m2.id, m3.id)
      )
      assertEquals(
        searchFor(
          MovementSearchCriteria(
            fundFlowId  = data.fundFlowConfig.id,
            createdFrom = Some(now.minusDays(400)),
            createdTo   = Some(now.minusDays(300))
          )
        ),
        Set.empty
      )

      // Reconciliation filter: m1 in a Final reconciliation, m2 in an InProgress one, m3 unreconciled.
      // Unreconciled excludes both reconciled movements; OpenBalance keeps the InProgress one too,
      // since only Final reconciliations net out of the balance.
      val finalRecon = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
      val inProgressRecon = Reconciliation(
        specId       = Some(data.reconSpec.id),
        appliedRules = AppliedRules.Manual,
        status       = ReconciliationStatus.InProgress
      )
      transact(transactor):
        reconRepo.insert(finalRecon)
        reconRepo.insert(inProgressRecon)
        movementRepo.setReconciliationIds(Seq(m1.id -> finalRecon.id, m2.id -> inProgressRecon.id))
      def searchByRecon(filter: ReconciliationFilter) =
        searchFor(MovementSearchCriteria(fundFlowId = data.fundFlowConfig.id, reconciliation = filter))
      try
        assertEquals(searchByRecon(ReconciliationFilter.All), Set(m1.id, m2.id, m3.id))
        assertEquals(searchByRecon(ReconciliationFilter.Unreconciled), Set(m3.id))
        assertEquals(searchByRecon(ReconciliationFilter.OpenBalance), Set(m2.id, m3.id))
      finally
        transact(transactor):
          reconRepo.deleteById(finalRecon.id)
          reconRepo.deleteById(inProgressRecon.id)
    finally teardown(data)
