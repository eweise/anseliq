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

class LedgerRoutes(
  transactor:    Transactor,
  ledgerRepo:    LedgerRepo,
  accountRepo:   AccountRepo,
  movementRepo:  MovementRepo,
  reconRepo:     ReconciliationRepo,
  ledgerService: LedgerService
) extends ApiRoutes:

  // Ledgers
  @cask.route(HttpPath.ledgers, methods = HttpCmd.GET)
  def getLedgers() =
    transact(transactor):
      val ledgers  = ledgerRepo.findAll
      val payloads = ledgers.map(ff => PayloadMapper.ledgerPayloadFromDomain(ff))
      write(payloads)

  @cask.route(s"${HttpPath.ledger}/:id/summary", methods = HttpCmd.GET)
  def getLedgerMainView(id: String) =
    transact(transactor):
      val ledgerId = UUID.fromString(id)
      val ledger   = ledgerRepo.findById(ledgerId).ErrIfNotFound("ledger", ledgerId)
      val accounts = accountRepo.findByLedger(ledgerId)

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
      val ledgerSummaryView = LedgerSummaryViewPayload(
        ledger   = PayloadMapper.ledgerPayloadFromDomain(ledger),
        accounts = accountSummaryViews
      )
      write(ledgerSummaryView)

  @cask.route(s"${HttpPath.ledger}", methods = HttpCmd.POST)
  def createLedger(request: cask.Request): String =
    val payload = read[LedgerPayload](request.text())
    transact(transactor):
      write(
        PayloadMapper.ledgerPayloadFromDomain(ledgerService.createLedger(PayloadMapper.ledgerPayloadToDomain(payload)))
      )

  @cask.route(s"${HttpPath.ledger}/:id", methods = HttpCmd.PUT)
  def updateLedger(id: String, request: cask.Request): String =
    val payload = read[LedgerPayload](request.text())
    val ffID    = UUID.fromString(payload.id.get)
    transact(transactor):
      val updated = ledgerService.updateLedger(ffID, payload.name, payload.dailyCutoffHours)
      write(PayloadMapper.ledgerPayloadFromDomain(updated))

  initialize()
