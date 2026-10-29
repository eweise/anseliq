package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class MovementSpecRoutes(
  transactor:          Transactor,
  movementSpecRepo:    MovementSpecRepo,
  movementSpecService: MovementSpecService
) extends ApiRoutes:

  // MovementSpec
  @cask.route(s"${HttpPath.ledgers}/:ledgerID/movement-specs", methods = HttpCmd.GET)
  def movementSpecsByLedgerID(ledgerID: String): String =
    transact(transactor):
      val specs   = movementSpecRepo.findByLedgerId(UUID.fromString(ledgerID))
      val payload = specs.map(PayloadMapper.movementSpecPayloadFromDomain)
      write(payload)

  // MovementSpec
  @cask.route(s"${HttpPath.movementSpec}", methods = HttpCmd.POST)
  def createMovementSpec(request: cask.Request): String =
    val payload = read[MovementSpecPayload](request.text())
    transact(transactor):
      val created = movementSpecService.createMovementSpec(PayloadMapper.movementSpecPayloadToDomain(payload))
      write(PayloadMapper.movementSpecPayloadFromDomain(created))

  @cask.route(s"${HttpPath.movementSpec}/:id", methods = HttpCmd.PUT)
  def updateMovementSpec(id: String, request: cask.Request): String =
    val payload = read[MovementSpecPayload](request.text())
    val specID  = UUID.fromString(id)
    transact(transactor):
      val updated = movementSpecService.updateMovementSpec(
        specID,
        name              = payload.name,
        inAccountId       = UUID.fromString(payload.inAccountId),
        outAccountId      = UUID.fromString(payload.outAccountId),
        fileFeedId        = UUID.fromString(payload.fileFeedId),
        routingFilterJson = PayloadMapper.routingFilterJson(payload)
      )
      write(PayloadMapper.movementSpecPayloadFromDomain(updated))

  @cask.delete(s"${HttpPath.movementSpec}/:id")
  def deleteMovementSpec(id: String): String =
    transact(transactor):
      movementSpecService.deleteMovementSpec(UUID.fromString(id))
    write("deleted")

  initialize()
