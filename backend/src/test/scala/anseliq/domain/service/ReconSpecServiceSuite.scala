package anseliq.domain.service

import anseliq.common.model.*
import anseliq.domain.DataSetup
import anseliq.domain.model.{IllegalArgumentError, ReconciliationSpec}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class ReconSpecServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def specWith(data: DataSetup, rule: Rule): ReconciliationSpec =
    ReconciliationSpec(
      accountId         = data.reconAccount.id,
      inMovementSpecId  = data.journalToRecon.id,
      outMovementSpecId = data.reportToRecon.id,
      ruleJson          = upickle.write[Rule](rule)
    )

  private def assertRejected(rule: Rule, expectedMessage: String): Unit =
    transact(app.transactor):
      rollBackOnly:
        val data  = DataSetup(app)
        val spec  = specWith(data, rule)
        val error = intercept[IllegalArgumentError](app.reconSpecService.createReconSpec(spec))
        assert(error.msg.contains(expectedMessage), error.msg)
        assertEquals(app.reconSpecRepo.findById(spec.id), None)

  test("createReconSpec saves a rule reconciliation can run"):
    transact(app.transactor):
      rollBackOnly:
        val data    = DataSetup(app)
        val created = app.reconSpecService.createReconSpec(specWith(data, data.rule))
        assertEquals(app.reconSpecRepo.findById(created.id).map(_.rule()), Some(data.rule))

  test("createReconSpec rejects an empty (NoOpRule) rule"):
    assertRejected(NoOpRule, "A reconciliation rule is required")

  test("createReconSpec rejects an AND rule with no conditions"):
    assertRejected(AndRule(Nil), "at least one condition")

  test("createReconSpec rejects an OR rule, which reconciliation doesn't support"):
    val leaf = StringAttributeMatchRule(StringAttribute(key = "id"), StringAttribute(key = "id"))
    assertRejected(ORRule(List(leaf, leaf)), "only supports")

  test("createReconSpec rejects swapped sides: the Primary side must flow into the Recon account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val swapped = specWith(data, data.rule).copy(
          inMovementSpecId  = data.reportToRecon.id,
          outMovementSpecId = data.journalToRecon.id
        )
        val error = intercept[IllegalArgumentError](app.reconSpecService.createReconSpec(swapped))
        assert(error.msg.contains("Primary side"), error.msg)

  test("updateReconSpec rejects an empty rule and leaves the saved spec unchanged"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val spec = data.reconSpec
        intercept[IllegalArgumentError]:
          app.reconSpecService.updateReconSpec(
            spec.id,
            agingLimit        = spec.agingLimit,
            relationship      = spec.relationship,
            inMovementSpecId  = spec.inMovementSpecId,
            outMovementSpecId = spec.outMovementSpecId,
            ruleJson          = upickle.write[Rule](NoOpRule)
          )
        assertEquals(app.reconSpecRepo.findById(spec.id).map(_.rule()), Some(data.rule))
