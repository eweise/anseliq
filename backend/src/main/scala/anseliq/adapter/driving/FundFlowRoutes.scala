package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.model.model.Zero
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class FundFlowRoutes(
  transactor:         Transactor,
  fundFlowConfigRepo: FundFlowConfigRepo,
  accountRepo:        AccountRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  fundFlowService:    FundFlowService
) extends ApiRoutes:

  // Fund Flows
  @cask.route(HttpPath.fundFlows, methods = HttpCmd.GET)
  def getFundFlows() =
    transact(transactor):
      val fundFlows = fundFlowConfigRepo.findAll
      val payloads  = fundFlows.map(ff => PayloadMapper.fundFlowPayloadFromDomain(ff))
      write(payloads)

  @cask.route(s"${HttpPath.fundFlow}/:id/summary", methods = HttpCmd.GET)
  def getFundFlowMainView(id: String) =
    transact(transactor):
      val fundFlowId = UUID.fromString(id)
      val fundFlow   = fundFlowConfigRepo.findById(fundFlowId).ErrIfNotFound("fund flow", fundFlowId)
      val accounts   = accountRepo.findByFundFlow(fundFlowId)

      val accountSummaryViews = accounts.map(account =>
        val isReconAccount    = account.accountType == AccountType.Recon
        val unreconciledCount = if isReconAccount then movementRepo.countUnreconciledByAccount(account.id) else 0
        val openTotals =
          if isReconAccount then reconRepo.openTotalsByAccountID(account.id).map(t => t.status -> t).toMap
          else Map.empty
        val inProgress = openTotals.get(ReconciliationStatus.InProgress)
        val mismatched = openTotals.get(ReconciliationStatus.Mismatched)

        AccountSummaryViewPayload(
          PayloadMapper.accountPayloadFromDomain(account),
          isReconAccount    = isReconAccount,
          unreconciledCount = unreconciledCount,
          inProgressCount   = inProgress.fold(0)(_.count),
          inProgressBalance = inProgress.fold(Zero)(_.balance),
          mismatchedCount   = mismatched.fold(0)(_.count),
          mismatchedBalance = mismatched.fold(Zero)(_.balance)
        )
      )
      val fundFlowSummaryView = FundFlowSummaryViewPayload(
        fundFlow = PayloadMapper.fundFlowPayloadFromDomain(fundFlow),
        accounts = accountSummaryViews
      )
      write(fundFlowSummaryView)

  @cask.route(s"${HttpPath.fundFlow}", methods = HttpCmd.POST)
  def createFundFlow(request: cask.Request): String =
    val payload = read[FundFlowPayload](request.text())
    transact(transactor):
      write(PayloadMapper.fundFlowPayloadFromDomain(fundFlowService.createFundFlow(PayloadMapper.fundFlowPayloadToDomain(payload))))

  @cask.route(s"${HttpPath.fundFlow}/:id", methods = HttpCmd.PUT)
  def updateFundFlow(id: String, request: cask.Request): String =
    val payload = read[FundFlowPayload](request.text())
    val ffID    = UUID.fromString(payload.id.get)
    transact(transactor):
      val updated = fundFlowService.updateFundFlow(ffID, payload.name, payload.dailyCutoffHours)
      write(PayloadMapper.fundFlowPayloadFromDomain(updated))

  initialize()
