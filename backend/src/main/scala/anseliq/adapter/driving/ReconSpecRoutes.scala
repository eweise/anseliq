package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class ReconSpecRoutes(
  transactor:       Transactor,
  reconSpecRepo:    ReconciliationSpecRepo,
  reconSpecService: ReconSpecService
) extends ApiRoutes:

  // ReconSpec
  @cask.route(s"${HttpPath.account}/:accountID/recon-specs", methods = HttpCmd.GET)
  def reconSpecsByaccountID(accountID: String): String =
    transact(transactor):
      val specs = reconSpecRepo.findByAccountID(UUID.fromString(accountID))
      specs.foreach(s => logger.debug(s"[reconSpecsByaccountID] id=${s.id} ruleJson=${s.ruleJson} rule=${s.rule()}"))
      val payload = specs.map(Payload.reconSpecPayloadFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.reconSpec}", methods = HttpCmd.POST)
  def createReconSpec(request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    transact(transactor):
      write(
        Payload.reconSpecPayloadFromDomain(reconSpecService.createReconSpec(Payload.reconSpecPayloadToDomain(payload)))
      )

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = HttpCmd.PUT)
  def updateReconSpec(id: String, request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    val specID  = UUID.fromString(id)
    transact(transactor):
      val updated = reconSpecService.updateReconSpec(
        specID,
        agingLimit        = payload.agingLimit,
        relationship      = Payload.reconRelationshipToDomain(payload.relationship),
        inMovementSpecId  = UUID.fromString(payload.inMovementSpecId),
        outMovementSpecId = UUID.fromString(payload.outMovementSpecId),
        ruleJson          = upickle.write(payload.rule)
      )
      write(Payload.reconSpecPayloadFromDomain(updated))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = Seq("delete"))
  def deleteReconSpec(id: String): String =
    transact(transactor):
      reconSpecService.deleteReconSpec(UUID.fromString(id))
    write("deleted")

  initialize()
