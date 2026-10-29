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
  @cask.route(s"${HttpPath.account}/:accountId/recon-specs", methods = HttpCmd.GET)
  def reconSpecsByAccountId(accountId: String): String =
    transact(transactor):
      val specs = reconSpecRepo.findByAccountId(UUID.fromString(accountId))
      specs.foreach(s => logger.debug(s"[reconSpecsByAccountId] id=${s.id} ruleJson=${s.ruleJson} rule=${s.rule()}"))
      val payload = specs.map(PayloadMapper.reconSpecPayloadFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.reconSpec}", methods = HttpCmd.POST)
  def createReconSpec(request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    transact(transactor):
      write(
        PayloadMapper.reconSpecPayloadFromDomain(
          reconSpecService.createReconSpec(PayloadMapper.reconSpecPayloadToDomain(payload))
        )
      )

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = HttpCmd.PUT)
  def updateReconSpec(id: String, request: cask.Request): String =
    val payload = read[ReconciliationSpecPayload](request.text())
    val specId  = UUID.fromString(id)
    transact(transactor):
      val updated = reconSpecService.updateReconSpec(
        specId,
        agingLimit        = payload.agingLimit,
        relationship      = PayloadMapper.reconRelationshipToDomain(payload.relationship),
        inMovementSpecId  = UUID.fromString(payload.inMovementSpecId),
        outMovementSpecId = UUID.fromString(payload.outMovementSpecId),
        ruleJson          = upickle.write(payload.rule)
      )
      write(PayloadMapper.reconSpecPayloadFromDomain(updated))

  @cask.route(s"${HttpPath.reconSpec}/:id", methods = Seq("delete"))
  def deleteReconSpec(id: String): String =
    transact(transactor):
      reconSpecService.deleteReconSpec(UUID.fromString(id))
    write("deleted")

  initialize()
