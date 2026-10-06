package anseliq.domain.service

import anseliq.adapter.driving.Payload
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import upickle.default.write as upickleWrite

import java.util.UUID

class FileFeedService(fileFeedRepo: FileFeedRepo):

  def createFileFeed(payload: FileFeedPayload): Txn[FileFeedPayload] =
    val feed = FileFeed(
      name                 = payload.name,
      dataSourceJson       = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse(""),
      expectedCadenceHours = payload.expectedCadenceHours
    )
    fileFeedRepo.insert(feed)
    Payload.fileFeedPayloadFromDomain(feed)

  def updateFileFeed(idStr: String, payload: FileFeedPayload): Txn[FileFeedPayload] =
    val id       = UUID.fromString(idStr)
    var existing = fileFeedRepo.findById(id).ErrIfNotFound("file feed", id)
    existing = existing.copy(
      name                 = payload.name,
      dataSourceJson       = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse(""),
      expectedCadenceHours = payload.expectedCadenceHours
    )
    fileFeedRepo.update(existing)
    Payload.fileFeedPayloadFromDomain(existing)

  def deleteFileFeed(idStr: String): Txn[Unit] =
    fileFeedRepo.deleteById(UUID.fromString(idStr))
